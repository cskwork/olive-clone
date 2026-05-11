package com.olive.commerce.inventory;

import com.olive.commerce.common.config.InventoryLockProperties;
import com.olive.commerce.common.config.RedissonConfig;
import com.olive.commerce.common.error.ErrorCode;
import com.olive.commerce.common.error.BusinessException;
import com.olive.commerce.common.persistence.PostgresIntegrationSupport;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OLV-031 Acceptance Criteria 1 동시성 테스트.
 *
 * <p>50개 virtual thread가 각각 1개씩 예약 시도 (재고 30개).
 * 정확히 30개 성공, 20개 INSUFFICIENT_INVENTORY.
 * available_quantity가 절대 0 미만이 되지 않음.
 *
 * <p>Testcontainers Redis + Redisson으로 실제 분산 락 환경 검증.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(classes = {
    RedisAutoConfiguration.class,
    DataSourceAutoConfiguration.class,
    DataSourceTransactionManagerAutoConfiguration.class,
    HibernateJpaAutoConfiguration.class
})
@Import({InventoryService.class, RedissonConfig.class, InventoryLockProperties.class})
@Testcontainers
class InventoryServiceConcurrentTest extends PostgresIntegrationSupport {

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

    /**
     * Testcontainers Redis (Redisson 연동용).
     * 기존 RedisIntegrationTest 패턴을 따른다 (llm-wiki/03-infra-baseline.md 줄 38-39).
     */
    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", redis::getFirstMappedPort);
    }

    private static final int INITIAL_STOCK = 30;
    private static final int THREAD_COUNT = 50;
    private Long testOptionId;

    @BeforeEach
    void setUp() {
        // 테스트용 product_option_id 생성 (product_options 테이블에서 ID 가져오기)
        testOptionId = ((Number) em.createNativeQuery(
                "SELECT id FROM product_options LIMIT 1"
        ).getSingleResult()).longValue();

        // 초기 재고 설정
        inventoryRepository.deleteAll();

        Inventory inventory = Inventory.create(testOptionId);
        inventory.addStock(INITIAL_STOCK);
        inventoryRepository.save(inventory);
        em.flush();
        em.clear();
    }

    @AfterEach
    void tearDown() {
        reservationRepository.deleteAll();
        inventoryRepository.deleteAll();
    }

    /**
     * AC1: 50 threads 동시 예약 → 정확히 30개 성공, 20개 실패.
     */
    @Test
    void reserve30Stock_concurrently50Threads_exactly30Succeed() throws Exception {
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
                            10000L + threadId, // 고유 orderId
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
        assertThat(successCount.get()).as("Success count").isEqualTo(30);
        assertThat(failureCount.get()).as("Failure count").isEqualTo(20);

        // DB 상태 검증: available_quantity는 절대 0 미만이면 안 됨
        Inventory finalInventory = inventoryRepository.findByProductOptionId(testOptionId)
                .orElseThrow();
        assertThat(finalInventory.getAvailableQuantity())
                .as("Available quantity must be >= 0")
                .isGreaterThanOrEqualTo(0);
        assertThat(finalInventory.getReservedQuantity())
                .as("Reserved quantity should be 30")
                .isEqualTo(30);

        // reservation 테이블도 검증
        long reservationCount = reservationRepository.count();
        assertThat(reservationCount)
                .as("Should have exactly 30 reservations")
                .isEqualTo(30);
    }

    /**
     * AC2: TTL 만료 → release → available 복구.
     */
    @Test
    void reserveWithTTL_waitForExpiration_releaseRestoresAvailable() throws Exception {
        // 1. 예약 (TTL=1초)
        inventoryService.reserve(
                20000L,
                List.of(new InventoryService.ReserveItem(testOptionId, 10)),
                Duration.ofSeconds(1)
        );

        em.flush();
        em.clear();

        Inventory afterReserve = inventoryRepository.findByProductOptionId(testOptionId).orElseThrow();
        assertThat(afterReserve.getReservedQuantity()).isEqualTo(10);
        assertThat(afterReserve.getAvailableQuantity()).isEqualTo(INITIAL_STOCK - 10);

        // 2. 2초 대기 (TTL 만료)
        Thread.sleep(2000);

        // 3. 만료 해제 배치 실행
        int released = inventoryService.releaseExpired();
        assertThat(released).isEqualTo(1);

        em.flush();
        em.clear();

        // 4. available 복구 검증
        Inventory afterRelease = inventoryRepository.findByProductOptionId(testOptionId).orElseThrow();
        assertThat(afterRelease.getReservedQuantity()).isEqualTo(0);
        assertThat(afterRelease.getAvailableQuantity()).isEqualTo(INITIAL_STOCK);
    }

    /**
     * AC3: commit 후 release는 idempotent (no-op, not error).
     */
    @Test
    void commitThenRelease_isIdempotent_noError() {
        // 1. 예약
        inventoryService.reserve(
                30000L,
                List.of(new InventoryService.ReserveItem(testOptionId, 5)),
                Duration.ofMinutes(15)
        );

        em.flush();
        em.clear();

        // 2. commit
        inventoryService.commit(30000L);

        em.flush();
        em.clear();

        Inventory afterCommit = inventoryRepository.findByProductOptionId(testOptionId).orElseThrow();
        assertThat(afterCommit.getTotalQuantity()).isEqualTo(INITIAL_STOCK - 5);
        assertThat(afterCommit.getReservedQuantity()).isEqualTo(0);

        // 3. release (idempotent)
        inventoryService.release(30000L, "결제 취소"); // 에러 없이 무시됨

        em.flush();
        em.clear();

        // 상태 변화 없음
        Inventory afterRelease = inventoryRepository.findByProductOptionId(testOptionId).orElseThrow();
        assertThat(afterRelease.getTotalQuantity()).as("Total should not change on release after commit")
                .isEqualTo(INITIAL_STOCK - 5);
        assertThat(afterRelease.getReservedQuantity()).as("Reserved should remain 0")
                .isEqualTo(0);
    }

    /**
     * AC4: Audit log 검증.
     */
    @Test
    void reserveCommit_writesAuditLogEntries() {
        inventoryService.reserve(
                40000L,
                List.of(new InventoryService.ReserveItem(testOptionId, 5)),
                Duration.ofMinutes(15)
        );

        inventoryService.commit(40000L);

        em.flush();
        em.clear();

        // history 테이블 검증
        List<InventoryHistory> histories = historyRepository.findByProductOptionIdOrderByCreatedAtDesc(testOptionId);
        assertThat(histories).hasSize(2);

        InventoryHistory reserveHistory = histories.get(1); // 두 번째 (오래된)
        assertThat(reserveHistory.getChangeType()).isEqualTo(InventoryHistory.ChangeType.RESERVE);
        assertThat(reserveHistory.getQuantityDelta()).isEqualTo(-5);
        assertThat(reserveHistory.getOrderId()).isEqualTo(40000L);

        InventoryHistory commitHistory = histories.get(0); // 첫 번째 (최신)
        assertThat(commitHistory.getChangeType()).isEqualTo(InventoryHistory.ChangeType.COMMIT);
        assertThat(commitHistory.getQuantityDelta()).isEqualTo(-5);
        assertThat(commitHistory.getOrderId()).isEqualTo(40000L);
    }

    /**
     * Multi-line order deadlock 방지 검증.
     */
    @Test
    void multiLineOrder_sortedLockPreventsDeadlock() throws Exception {
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
        em.flush();
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

                    inventoryService.reserve(50000L + threadId, items, Duration.ofMinutes(15));
                    successCount.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        // 모든 스레드가 10초 내에 완료되어야 함 (deadlock 없음)
        boolean completed = latch.await(10, TimeUnit.SECONDS);

        assertThat(completed).as("Should complete without deadlock").isTrue();
        assertThat(successCount.get()).as("All threads should succeed").isEqualTo(10);

        executor.shutdown();
    }
}
