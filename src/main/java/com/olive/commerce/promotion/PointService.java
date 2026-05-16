package com.olive.commerce.promotion;

import com.olive.commerce.common.audit.AuditLogger;
import com.olive.commerce.common.error.BusinessException;
import com.olive.commerce.common.error.ErrorCode;
import com.olive.commerce.promotion.PointHistory.ChangeType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 포인트 서비스.
 * <p>포인트 적립, 사용, 취소, 소멸 기능을 제공합니다.
 * <p>V6 트리거({@code update_points_balance()})가 {@code points.balance}를 자동 갱신하므로
 * 서비스는 원장 기록에 집중합니다.
 */
@Service
public class PointService {

    private static final Logger log = LoggerFactory.getLogger(PointService.class);

    private final PointHistoryRepository pointHistoryRepository;
    private final AuditLogger auditLogger;

    public PointService(
            PointHistoryRepository pointHistoryRepository,
            AuditLogger auditLogger
    ) {
        this.pointHistoryRepository = pointHistoryRepository;
        this.auditLogger = auditLogger;
    }

    /**
     * 포인트를 적립합니다 (미래 사용 가능일 지정).
     *
     * @param memberId    회원 ID
     * @param amount      적립 금액
     * @param orderId     연관 주문 ID
     * @param availableAt 사용 가능 일시
     * @param expiresAt   소멸 일시 (null = 무기한)
     */
    @Transactional
    public void earnScheduled(
            Long memberId,
            BigDecimal amount,
            Long orderId,
            OffsetDateTime availableAt,
            OffsetDateTime expiresAt
    ) {
        String reason = orderId != null ? "주문 #" + orderId + " 적립" : "관리자 적립";
        PointHistory history = PointHistory.earn(memberId, amount, reason, orderId, availableAt, expiresAt);
        pointHistoryRepository.save(history);

        // Map.of()는 null 값을 허용하지 않으므로 HashMap 사용
        Map<String, Object> logData = new HashMap<>();
        logData.put("memberId", memberId);
        logData.put("amount", amount);
        if (orderId != null) {
            logData.put("orderId", orderId);
        }
        logData.put("availableAt", availableAt);
        if (expiresAt != null) {
            logData.put("expiresAt", expiresAt);
        }
        auditLogger.log("POINT_EARN", logData);
    }

    /**
     * 포인트를 사용합니다.
     * <p>동시성 제어를 위해 {@code SELECT FOR UPDATE}로 회원의 포인트 행을 잠급니다.
     *
     * @param memberId 회원 ID
     * @param amount   사용 금액
     * @param orderId  연관 주문 ID
     * @throws ErrorCode#INSUFFICIENT_POINTS 잔액 부족 시
     */
    @Transactional
    public void use(Long memberId, BigDecimal amount, Long orderId) {
        // FOR UPDATE로 잠금 획득 (동시성 제어)
        pointHistoryRepository.lockByMemberIdForUpdate(memberId);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        // 사용 가능 잔액 확인
        BigDecimal spendable = spendableBalance(memberId, now);
        if (spendable.compareTo(amount) < 0) {
            throw new BusinessException(ErrorCode.INSUFFICIENT_POINTS,
                    "memberId=" + memberId + ", spendable=" + spendable + ", requested=" + amount);
        }

        // 사용 내역 기록
        String reason = orderId != null ? "주문 #" + orderId + " 사용" : "포인트 사용";
        PointHistory history = PointHistory.use(memberId, amount, reason, orderId, now);
        pointHistoryRepository.save(history);

        // Map.of()는 null 값을 허용하지 않으므로 HashMap 사용
        Map<String, Object> logData = new HashMap<>();
        logData.put("memberId", memberId);
        logData.put("amount", amount);
        if (orderId != null) {
            logData.put("orderId", orderId);
        }
        logData.put("remainingBalance", spendable.subtract(amount));
        auditLogger.log("POINT_USE", logData);
    }

    /**
     * 주문 취소 시 포인트를 복구합니다.
     * <p>해당 주문의 EARN 및 USE 내역을 찾아 CANCEL 내역을 생성합니다.
     *
     * @param memberId 회원 ID
     * @param orderId  취소된 주문 ID
     */
    @Transactional
    public void cancel(Long memberId, Long orderId) {
        List<PointHistory> histories = pointHistoryRepository.findByOrderId(orderId);
        if (histories.isEmpty()) {
            return; // 취소할 포인트 내역 없음 (멱등성)
        }

        for (PointHistory h : histories) {
            if (!h.getMemberId().equals(memberId)) {
                continue; // 다른 회원의 내역은 건너뜀
            }

            ChangeType type = h.getChangeType();
            BigDecimal amount = h.getAmount();
            String reason = "주문 #" + orderId + " 취소";

            if (type == ChangeType.EARN) {
                // 적립 취소: CANCEL 행 생성 (적립을 상쇄)
                PointHistory cancel = PointHistory.cancel(memberId, amount, reason + " (적립)", orderId);
                pointHistoryRepository.save(cancel);
            } else if (type == ChangeType.USE) {
                // 사용 취소: CANCEL 행 생성 (사용을 상쇄 = 포인트 반환)
                PointHistory cancel = PointHistory.cancel(memberId, amount, reason + " (사용)", orderId);
                pointHistoryRepository.save(cancel);
            }
        }

        auditLogger.log("POINT_CANCEL", Map.of(
                "memberId", memberId,
                "orderId", orderId,
                "cancelledCount", histories.size()
        ));
    }

    /**
     * 만료된 포인트를 소멸 처리합니다.
     *
     * @param memberId 회원 ID
     * @param asOf     기준 시점
     */
    @Transactional
    public void expire(Long memberId, OffsetDateTime asOf) {
        List<PointHistory> expiredEarns = pointHistoryRepository.findExpiredEarns(asOf);

        for (PointHistory earn : expiredEarns) {
            if (!earn.getMemberId().equals(memberId)) {
                continue;
            }

            // 이미 EXPIRE 행이 있는지 확인 (간단 구현을 위해 생략 - 중복 소멸 방지 필요 시 추가)
            String reason = "포인트 소멸 (history #" + earn.getId() + ")";
            PointHistory expire = PointHistory.expire(memberId, earn.getAmount(), reason, earn.getId());
            pointHistoryRepository.save(expire);
        }

        auditLogger.log("POINT_EXPIRE", Map.of(
                "memberId", memberId,
                "asOf", asOf,
                "expiredCount", expiredEarns.size()
        ));
    }

    /**
     * 사용 가능한 포인트 잔액을 계산합니다.
     * <p>원장에서 직접 집계하므로 항상 정확한 값을 반환합니다.
     *
     * @param memberId 회원 ID
     * @param asOf     기준 시점 (null = 현재)
     * @return 사용 가능 잔액
     */
    @Transactional(readOnly = true)
    public BigDecimal spendableBalance(Long memberId, OffsetDateTime asOf) {
        if (asOf == null) {
            asOf = OffsetDateTime.now(ZoneOffset.UTC);
        }
        return pointHistoryRepository.sumSpendableBalance(memberId, asOf);
    }

    /**
     * 대기 중인 포인트 목록을 조회합니다.
     *
     * @param memberId 회원 ID
     * @param days     조회 기간 (일)
     * @return 대기 중인 포인트 목록
     */
    @Transactional(readOnly = true)
    public List<PointHistory> getPendingPoints(Long memberId, int days) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime cutoff = now.plusDays(days);
        return pointHistoryRepository.findPendingByMemberIdBetween(memberId, now, cutoff);
    }

    /**
     * 대기 중인 포인트 목록을 조회합니다 (기준 시점 지정).
     *
     * @param memberId 회원 ID
     * @param days     조회 기간 (일)
     * @param asOf     기준 시점 (null = 현재)
     * @return 대기 중인 포인트 목록
     */
    @Transactional(readOnly = true)
    public List<PointHistory> getPendingPoints(Long memberId, int days, OffsetDateTime asOf) {
        if (asOf == null) {
            asOf = OffsetDateTime.now(ZoneOffset.UTC);
        }
        OffsetDateTime cutoff = asOf.plusDays(days);
        return pointHistoryRepository.findPendingByMemberIdBetween(memberId, asOf, cutoff);
    }

    /**
     * 포인트 내역을 페이징 조회합니다.
     *
     * @param memberId 회원 ID
     * @param pageable 페이징 정보
     * @return 포인트 내역 페이지
     */
    @Transactional(readOnly = true)
    public Page<PointHistory> getHistory(Long memberId, Pageable pageable) {
        return pointHistoryRepository.findByMemberIdOrderByCreatedAtDesc(memberId, pageable);
    }

    /**
     * 배송 완료 시 예약된 적립 포인트를 즉시 사용 가능하도록 전환합니다.
     * <p>해당 주문의 미래 사용 가능일(available_at)인 EARN 내역을 찾아
     * CANCEL로 복구하고 즉시 사용 가능한 새 EARN 내역을 생성합니다.
     *
     * @param memberId 회원 ID
     * @param orderId  주문 ID
     */
    @Transactional
    public void flipScheduledToSpendable(Long memberId, Long orderId) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        // 미래 사용 가능일인 EARN 내역 조회
        List<PointHistory> pendingEarns = pointHistoryRepository.findByOrderIdAndChangeTypeAndAvailableAtAfter(
                orderId, ChangeType.EARN, now
        );

        if (pendingEarns.isEmpty()) {
            log.debug("No pending points to flip for order: {}", orderId);
            return;
        }

        for (PointHistory earn : pendingEarns) {
            if (!earn.getMemberId().equals(memberId)) {
                continue; // 다른 회원의 내역은 건너뜀
            }

            // 기존 예약 적립 취소
            String cancelReason = "주문 #" + orderId + " 배송 완료로 즉시 적립 전환 (기존 예약 취소)";
            PointHistory cancel = PointHistory.cancel(memberId, earn.getAmount(), cancelReason, orderId);
            pointHistoryRepository.save(cancel);

            // 즉시 사용 가능한 새 적립 생성
            String newReason = "주문 #" + orderId + " 배송 완료로 즉시 적립";
            PointHistory newEarn = PointHistory.earn(
                    memberId,
                    earn.getAmount(),
                    newReason,
                    orderId,
                    now, // 즉시 사용 가능
                    earn.getExpiresAt() // 만료일은 그대로
            );
            pointHistoryRepository.save(newEarn);

            log.debug("Flipped pending points: orderId={}, memberId={}, amount={}",
                    orderId, memberId, earn.getAmount());
        }

        auditLogger.log("POINT_FLIP_TO_SPENDABLE", Map.of(
                "memberId", memberId,
                "orderId", orderId,
                "flippedCount", pendingEarns.size()
        ));
    }
}
