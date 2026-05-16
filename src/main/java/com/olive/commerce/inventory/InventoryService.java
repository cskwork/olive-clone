package com.olive.commerce.inventory;

import com.olive.commerce.common.audit.AuditLogger;
import com.olive.commerce.common.config.InventoryLockProperties;
import com.olive.commerce.common.error.BusinessException;
import com.olive.commerce.common.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Inventory 도메인 서비스 (OLV-031).
 *
 * <p>분산 락(Redisson RLock)을 사용한 reserve-then-commit 패턴 구현 (PRD §20.5).
 *
 * <p>Multi-line orders는 **sorted option_id 순서로 락 획득**하여 deadlock 방지 (PRD §10.2).
 * 락 해제는 역순으로 진행한다.
 *
 * <p>Redis 다운 시 feature flag {@code inventory.lock.fallbackToDb=true}로
 * DB 락({@code SELECT ... FOR UPDATE})으로 폴백한다.
 */
@Service
@RequiredArgsConstructor
public class InventoryService {

    private static final Logger log = LoggerFactory.getLogger(InventoryService.class);

    private final InventoryRepository inventoryRepository;
    private final InventoryHistoryRepository historyRepository;
    private final InventoryReservationRepository reservationRepository;
    private final ObjectProvider<RedissonClient> redissonClientProvider;
    private final InventoryLockProperties lockProperties;
    private final AuditLogger auditLogger;

    /**
     * 주문 예약 (atomic across all items, PRD §20.5).
     *
     * <p>모든 아이템의 재고가 충분하면 **전부 예약**하고, 부족하면 **아무것도 예약하지 않는다** (ALL-or-NONE).
     *
     * <p>락 전략 분기:
     * <ul>
     *   <li>기본: Redisson RLock (분산 락)</li>
     *   <li>Fallback: {@code inventory.lock.fallbackToDb=true} 시 DB {@code SELECT ... FOR UPDATE}</li>
     * </ul>
     *
     * @param orderId 주문 ID
     * @param items    예약 아이템 목록 (optionId, qty)
     * @param ttl      예약 TTL (기본 15분)
     */
    @Transactional
    public void reserve(Long orderId, List<ReserveItem> items, Duration ttl) {
        if (items == null || items.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Reserve items cannot be empty");
        }

        // Feature flag: DB 락 fallback (AC5)
        if (lockProperties.isFallbackToDb()) {
            reserveWithDbLock(orderId, items, ttl);
            return;
        }

        // Default: Redisson RLock 경로
        reserveWithRedisLock(orderId, items, ttl);
    }

    /**
     * Redisson RLock을 사용한 예약 (기본 경로).
     */
    private void reserveWithRedisLock(Long orderId, List<ReserveItem> items, Duration ttl) {
        RedissonClient redissonClient = redissonClientProvider.getIfAvailable();
        if (redissonClient == null) {
            if (lockProperties.isFallbackToDb()) {
                reserveWithDbLock(orderId, items, ttl);
                return;
            }
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "Redisson client is not available");
        }

        // option_id 정렬 (deadlock 방지, PRD §10.2)
        List<Long> sortedOptionIds = items.stream()
                .map(ReserveItem::optionId)
                .sorted()
                .distinct()
                .toList();

        List<RLock> locks = new ArrayList<>();

        try {
            // 모든 락 획득
            for (Long optionId : sortedOptionIds) {
                RLock lock = redissonClient.getLock("lock:inv:" + optionId);
                locks.add(lock);
                boolean acquired = lock.tryLock(
                        lockProperties.getLockWaitTimeSeconds(),
                        lockProperties.getLockLeaseTimeSeconds(),
                        TimeUnit.SECONDS
                );
                if (!acquired) {
                    throw new BusinessException(ErrorCode.LOCK_ACQUISITION_FAILED,
                            "Failed to acquire lock for option: " + optionId);
                }
            }

            // 락 내부에서 재고 검증 + 예약
            doReserve(orderId, items, ttl);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "Lock acquisition interrupted: " + e.getMessage());
        } finally {
            // 역순 해제
            Collections.reverse(locks);
            for (RLock lock : locks) {
                try {
                    if (lock.isHeldByCurrentThread()) {
                        lock.unlock();
                    }
                } catch (Exception e) {
                    log.warn("Failed to release lock: {}", e.getMessage());
                }
            }
        }
    }

    /**
     * Redis 다운 시 DB 락 fallback 경로 (PRD §15.4).
     * feature flag {@code inventory.lock.fallbackToDb=true}일 때만 사용된다.
     *
     * <p>{@code SELECT ... FOR UPDATE}로 DB 락 획득 후 예약 수행.
     */
    @Transactional
    public void reserveWithDbLock(Long orderId, List<ReserveItem> items, Duration ttl) {
        if (items == null || items.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Reserve items cannot be empty");
        }

        // option_id 정렬 (deadlock 방지, PRD §10.2)
        List<Long> sortedOptionIds = items.stream()
                .map(ReserveItem::optionId)
                .sorted()
                .distinct()
                .toList();

        // 1. 모든 옵션에 대해 PESSIMISTIC_WRITE 락 획득 (검증 전)
        // JPA 트랜잭션 내에서 락은 커밋까지 유지됨
        for (Long optionId : sortedOptionIds) {
            Inventory locked = inventoryRepository.findByProductOptionIdForUpdate(optionId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_OPTION_NOT_FOUND,
                            "Inventory not found for option: " + optionId));
            // 엔티티를 로드하기만 하면 락이 유지됨 (Hibernate 1차 캐시)
        }

        // 2. 락 획득 후 재고 검증 + 예약
        // doReserve 내부의 findByProductOptionId는 같은 트랜잭션에서
        // 이미 락이 걸린 엔티티를 반환함 (1차 캐시)
        doReserve(orderId, items, ttl);
    }

    /**
     * 실제 예약 로직 (공통).
     */
    private void doReserve(Long orderId, List<ReserveItem> items, Duration ttl) {
        Map<Long, Inventory> inventories = new HashMap<>();
        List<Long> sortedOptionIds = items.stream()
                .map(ReserveItem::optionId)
                .sorted()
                .distinct()
                .toList();
        for (Long optionId : sortedOptionIds) {
            Inventory inventory = inventoryRepository.findByProductOptionIdForUpdate(optionId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_OPTION_NOT_FOUND,
                            "Inventory not found for option: " + optionId));
            inventories.put(optionId, inventory);
        }

        // 1. 모든 아이템의 재고 검증
        for (ReserveItem item : items) {
            Inventory inventory = inventories.get(item.optionId());

            if (!inventory.hasAvailable(item.quantity())) {
                throw new BusinessException(ErrorCode.INSUFFICIENT_INVENTORY,
                        "Insufficient inventory for option: " + item.optionId() +
                                ", available: " + inventory.getAvailableQuantity() +
                                ", requested: " + item.quantity());
            }
        }

        // 2. 모든 아이템 예약 (검증 통과 후)
        for (ReserveItem item : items) {
            Inventory inventory = inventories.get(item.optionId());

            // 중복 예약 방지 (UNIQUE 제약 활용)
            if (reservationRepository.findByOrderIdAndProductOptionId(orderId, item.optionId()).isPresent()) {
                continue; // 이미 예약됨 (idempotent)
            }

            inventory.reserve(item.quantity());
            inventoryRepository.save(inventory);

            InventoryHistory history = InventoryHistory.system(
                    item.optionId(),
                    InventoryHistory.ChangeType.RESERVE,
                    -item.quantity(),
                    "주문 예약",
                    orderId
            );
            historyRepository.save(history);

            InventoryReservation reservation = InventoryReservation.createHeld(
                    orderId, item.optionId(), item.quantity(), ttl
            );
            reservationRepository.save(reservation);
        }

        auditLog("INVENTORY_RESERVED", orderId, items);
    }

    /**
     * 결제 승인 → 예약 확정 (PRD §20.5).
     *
     * <p>reserved_quantity 감소 + total_quantity 감소 + 예약 상태 COMMITTED.
     *
     * @param orderId 주문 ID
     */
    @Transactional
    public void commit(Long orderId) {
        List<InventoryReservation> reservations = reservationRepository.findByOrderId(orderId);
        if (reservations.isEmpty()) {
            log.warn("No reservations found for order: {}", orderId);
            return; // idempotent
        }

        for (InventoryReservation reservation : reservations) {
            if (reservation.getStatus() == InventoryReservation.ReservationStatus.COMMITTED) {
                continue; // 이미 확정됨 (idempotent)
            }

            Inventory inventory = inventoryRepository.findByProductOptionId(reservation.getProductOptionId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_OPTION_NOT_FOUND,
                            "Inventory not found for option: " + reservation.getProductOptionId()));

            inventory.commit(reservation.getQuantity());
            inventoryRepository.save(inventory);

            InventoryHistory history = InventoryHistory.system(
                    reservation.getProductOptionId(),
                    InventoryHistory.ChangeType.COMMIT,
                    -reservation.getQuantity(),
                    "결제 승인",
                    orderId
            );
            historyRepository.save(history);

            reservation.commit();
            reservationRepository.save(reservation);
        }

        auditLog("INVENTORY_COMMITTED", orderId, reservations);
    }

    /**
     * 결제 실패 또는 TTL 만료 → 예약 해제 (PRD §20.5).
     *
     * <p>reserved_quantity만 감소. total_quantity는 변하지 않음.
     * 이미 COMMITTED된 예약은 무시한다 (idempotent).
     *
     * @param orderId 주문 ID
     * @param reason  해제 사유
     */
    @Transactional
    public void release(Long orderId, String reason) {
        List<InventoryReservation> reservations = reservationRepository.findByOrderId(orderId);
        if (reservations.isEmpty()) {
            log.warn("No reservations found for order: {}", orderId);
            return; // idempotent
        }

        for (InventoryReservation reservation : reservations) {
            boolean released = reservation.release(false); // alreadyCommitted=false

            if (!released) {
                // 이미 COMMITTED 또는 RELEASED 상태
                continue;
            }

            Inventory inventory = inventoryRepository.findByProductOptionId(reservation.getProductOptionId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_OPTION_NOT_FOUND,
                            "Inventory not found for option: " + reservation.getProductOptionId()));

            inventory.release(reservation.getQuantity());
            inventoryRepository.save(inventory);

            InventoryHistory history = InventoryHistory.system(
                    reservation.getProductOptionId(),
                    InventoryHistory.ChangeType.RELEASE,
                    reservation.getQuantity(),
                    reason,
                    orderId
            );
            historyRepository.save(history);

            reservationRepository.save(reservation);
        }

        auditLog("INVENTORY_RELEASED", orderId, reservations);
    }

    /**
     * 관리자 수동 재고 조정 (ADMIN_ADJUST, PRD §7.4).
     *
     * @param optionId 옵션 ID
     * @param delta    변화량 (양수=증가, 음수=감소)
     * @param reason   사유
     * @param adminId  관리자 ID
     */
    @Transactional
    public void adjust(Long optionId, int delta, String reason, Long adminId) {
        Inventory inventory = inventoryRepository.findByProductOptionId(optionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_OPTION_NOT_FOUND,
                        "Inventory not found for option: " + optionId));

        if (delta > 0) {
            inventory.addStock(delta);
        } else if (delta < 0) {
            inventory.removeStock(-delta);
        } else {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Delta cannot be zero");
        }

        inventoryRepository.save(inventory);

        InventoryHistory history = InventoryHistory.manual(
                optionId, delta, reason, adminId
        );
        historyRepository.save(history);

        auditLog("INVENTORY_ADJUSTED", optionId, delta, reason, adminId);
    }

    /**
     * 만료된 예약 일괄 해제 (배치 작업용, PRD §17.2).
     *
     * @return 해제된 예약 수
     */
    @Transactional
    public int releaseExpired() {
        List<InventoryReservation> expired = reservationRepository.findExpiredHeldReservations(Instant.now());
        int count = 0;

        for (InventoryReservation reservation : expired) {
            boolean released = reservation.release(false);

            if (!released) {
                continue;
            }

            Inventory inventory = inventoryRepository.findByProductOptionId(reservation.getProductOptionId())
                    .orElseThrow();

            inventory.release(reservation.getQuantity());
            inventoryRepository.save(inventory);

            InventoryHistory history = InventoryHistory.system(
                    reservation.getProductOptionId(),
                    InventoryHistory.ChangeType.RELEASE,
                    reservation.getQuantity(),
                    "TTL 만료 자동 해제",
                    reservation.getOrderId()
            );
            historyRepository.save(history);

            reservationRepository.save(reservation);
            count++;
        }

        log.info("Released {} expired reservations", count);

        if (count > 0) {
            auditLog("INVENTORY_EXPIRED_RELEASED", count);
        }

        return count;
    }

    /**
     * 옵션 ID로 재고 조회 (Admin API용).
     */
    public Inventory findByOptionId(Long optionId) {
        return inventoryRepository.findByProductOptionId(optionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_OPTION_NOT_FOUND,
                        "Inventory not found for option: " + optionId));
    }

    /**
     * 상품 ID의 모든 옵션 재고 조회 (Admin API용).
     * 일단 빈 구현 — 추후 product_option 조인 필요.
     */
    public List<Inventory> findByProductId(Long productId) {
        // TODO: product_option 테이블과 조인 필요
        // 일단 전체 반환
        return inventoryRepository.findAll();
    }

    /**
     * 예약 아이템 DTO.
     */
    public record ReserveItem(
            Long optionId,
            int quantity
    ) {
        public ReserveItem {
            if (quantity <= 0) {
                throw new IllegalArgumentException("Quantity must be positive: " + quantity);
            }
        }
    }

    /**
     * Audit logging helper.
     */
    private void auditLog(String event, Object... data) {
        try {
            auditLogger.log(event, java.util.Map.of(
                    "data", java.util.Arrays.toString(data)
            ));
        } catch (Exception e) {
            log.warn("Failed to write audit log for event {}: {}", event, e.getMessage());
        }
    }
}
