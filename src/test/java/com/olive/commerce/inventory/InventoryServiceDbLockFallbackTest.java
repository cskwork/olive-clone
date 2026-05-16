package com.olive.commerce.inventory;

import com.olive.commerce.common.audit.AuditLogger;
import com.olive.commerce.common.config.InventoryLockProperties;
import com.olive.commerce.common.error.ErrorCode;
import com.olive.commerce.common.error.BusinessException;
import com.olive.commerce.common.persistence.PostgresIntegrationSupport;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OLV-031 Acceptance Criteria 5: Redis-down fallback 테스트.
 *
 * <p>{@code inventory.lock.fallbackToDb=true} 설정 시
 * DB {@code SELECT ... FOR UPDATE} 락으로 폴백하는 경로를 검증.
 *
 * <p>Redis 의존성 없이 DB 락만으로 동시성 제어가 정상 작동하는지 확인.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(classes = {
    DataSourceAutoConfiguration.class,
    DataSourceTransactionManagerAutoConfiguration.class,
    HibernateJpaAutoConfiguration.class
})
@Import({InventoryService.class})
@EnableConfigurationProperties(InventoryLockProperties.class)
@TestPropertySource(properties = {
    "inventory.lock.fallbackToDb=true"
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class InventoryServiceDbLockFallbackTest extends PostgresIntegrationSupport {

    @Autowired
    private InventoryService inventoryService;

    @Autowired
    private InventoryRepository inventoryRepository;

    @Autowired
    private InventoryHistoryRepository historyRepository;

    @Autowired
    private InventoryReservationRepository reservationRepository;

    @Autowired
    private EntityManager em;

    @MockBean
    private AuditLogger auditLogger;

    private static final int INITIAL_STOCK = 30;
    private static final int THREAD_COUNT = 50;
    private Long testOptionId;

    @BeforeEach
    void setUp() {
        // 테스트용 product_option_id 생성
        testOptionId = ((Number) em.createNativeQuery(
                "SELECT id FROM product_options LIMIT 1"
        ).getSingleResult()).longValue();

        // 초기 재고 설정
        inventoryRepository.deleteAll();

        Inventory inventory = Inventory.create(testOptionId);
        inventory.addStock(INITIAL_STOCK);
        inventoryRepository.save(inventory);
        em.clear();
    }

    @AfterEach
    void tearDown() {
        reservationRepository.deleteAll();
        inventoryRepository.deleteAll();
    }

    /**
     * AC5: Redis-down fallback 시 50 threads 동시 예약 → 정확히 30개 성공, 20개 실패.
     *
     * <p>DB {@code SELECT ... FOR UPDATE} 락으로 동시성 제어 검증.
     */
    @Test
    void reserve30Stock_withDbLockFallback_concurrently50Threads_exactly30Succeed() throws Exception {
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);
        List<Throwable> exceptions = new CopyOnWriteArrayList<>();

        // 50개 virtual thread 생성 (Java 21)
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (int i = 0; i < THREAD_COUNT; i++) {
            final int threadId = i;
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                try {
                    inventoryService.reserve(
                            60000L + threadId, // 고유 orderId
                            List.of(new InventoryService.ReserveItem(testOptionId, 1)),
                            Duration.ofMinutes(15)
                    );
                    successCount.incrementAndGet();
                } catch (BusinessException e) {
                    if (e.errorCode() == ErrorCode.INSUFFICIENT_INVENTORY) {
                        failureCount.incrementAndGet();
                    } else {
                        exceptions.add(e);
                    }
                } catch (Exception e) {
                    exceptions.add(e);
                }
            }, executor);
            futures.add(future);
        }

        // 모든 thread가 완료될 때까지 대기 (최대 30초)
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .get(30, TimeUnit.SECONDS);

        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);

        // 예외가 없어야 함
        assertThat(exceptions).withFailMessage("Unexpected exceptions: %s", exceptions).isEmpty();

        // 정확히 30개 성공, 20개 실패
        assertThat(successCount.get()).as("Success count with DB lock").isEqualTo(30);
        assertThat(failureCount.get()).as("Failure count with DB lock").isEqualTo(20);

        // DB 상태 검증: available_quantity는 절대 0 미만이면 안 됨
        Inventory finalInventory = inventoryRepository.findByProductOptionId(testOptionId)
                .orElseThrow();
        assertThat(finalInventory.getAvailableQuantity())
                .as("Available quantity must be >= 0 with DB lock")
                .isGreaterThanOrEqualTo(0);
        assertThat(finalInventory.getReservedQuantity())
                .as("Reserved quantity should be 30 with DB lock")
                .isEqualTo(30);

        // reservation 테이블도 검증
        long reservationCount = reservationRepository.count();
        assertThat(reservationCount)
                .as("Should have exactly 30 reservations with DB lock")
                .isEqualTo(30);
    }

    /**
     * AC5 추가: Multi-line order deadlock 방지 (DB 락 경로).
     */
    @Test
    void multiLineOrder_withDbLockFallback_sortedLockPreventsDeadlock() throws Exception {
        // 옵션 2개 준비
        Long optionId2 = ((Number) em.createNativeQuery(
                "SELECT id FROM product_options WHERE id != :id LIMIT 1"
        ).setParameter("id", testOptionId)
                .getSingleResult()).longValue();

        if (optionId2 == null) {
            // 옵션이 하나만 있으면 테스트 스킵
            return;
        }

        Inventory inventory2 = Inventory.create(optionId2);
        inventory2.addStock(INITIAL_STOCK);
        inventoryRepository.save(inventory2);
        em.clear();

        // 10개 스레드가 각각 다른 순서로 예약 시도
        ExecutorService executor = Executors.newFixedThreadPool(10);
        CountDownLatch latch = new CountDownLatch(10);
        AtomicInteger successCount = new AtomicInteger(0);

        for (int i = 0; i < 10; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    // 순서를 섞어서 (deadlock 유도 시도)
                    List<InventoryService.ReserveItem> items = (threadId % 2 == 0)
                            ? List.of(
                                    new InventoryService.ReserveItem(testOptionId, 1),
                                    new InventoryService.ReserveItem(optionId2, 1)
                            )
                            : List.of(
                                    new InventoryService.ReserveItem(optionId2, 1),
                                    new InventoryService.ReserveItem(testOptionId, 1)
                            );

                    inventoryService.reserve(70000L + threadId, items, Duration.ofMinutes(15));
                    successCount.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        // 모든 스레드가 10초 내에 완료되어야 함 (deadlock 없음)
        boolean completed = latch.await(10, TimeUnit.SECONDS);

        assertThat(completed).as("Should complete without deadlock with DB lock").isTrue();
        assertThat(successCount.get()).as("All threads should succeed with DB lock").isEqualTo(10);

        executor.shutdown();
    }

    /**
     * AC5 추가: Audit log도 DB 락 경로에서 정상 기록되는지 검증.
     */
    @Test
    void reserveCommit_withDbLockFallback_writesAuditLogEntries() {
        inventoryService.reserve(
                80000L,
                List.of(new InventoryService.ReserveItem(testOptionId, 5)),
                Duration.ofMinutes(15)
        );

        inventoryService.commit(80000L);

        em.clear();

        // history 테이블 검증
        List<InventoryHistory> histories = historyRepository.findByProductOptionIdOrderByCreatedAtDesc(testOptionId);
        assertThat(histories).hasSize(2);

        InventoryHistory reserveHistory = histories.get(1);
        assertThat(reserveHistory.getChangeType()).isEqualTo(InventoryHistory.ChangeType.RESERVE);
        assertThat(reserveHistory.getQuantityDelta()).isEqualTo(-5);
        assertThat(reserveHistory.getOrderId()).isEqualTo(80000L);

        InventoryHistory commitHistory = histories.get(0);
        assertThat(commitHistory.getChangeType()).isEqualTo(InventoryHistory.ChangeType.COMMIT);
        assertThat(commitHistory.getQuantityDelta()).isEqualTo(-5);
        assertThat(commitHistory.getOrderId()).isEqualTo(80000L);
    }
}
