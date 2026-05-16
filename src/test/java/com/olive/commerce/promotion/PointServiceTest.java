package com.olive.commerce.promotion;

import com.olive.commerce.common.audit.AuditLogger;
import com.olive.commerce.common.error.ErrorCode;
import com.olive.commerce.common.error.BusinessException;
import com.olive.commerce.member.Member;
import com.olive.commerce.member.MemberGrade;
import com.olive.commerce.member.MemberGradeRepository;
import com.olive.commerce.member.MemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 포인트 서비스 통합 테스트.
 * <p>AC 5건을 모두 검증합니다.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class PointServiceTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
    }

    @Autowired
    private PointService pointService;

    @Autowired
    private PointHistoryRepository pointHistoryRepository;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private MemberGradeRepository memberGradeRepository;

    @MockBean
    private AuditLogger auditLogger;

    private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 5, 11, 0, 0, 0, 0, ZoneOffset.UTC);

    private Long MEMBER_1_ID;
    private Long MEMBER_2_ID;

    @BeforeEach
    void setUp() {
        // 멤버 삭제로 연관된 point_histories와 points를 CASCADE로 정리
        memberRepository.deleteAll();

        MemberGrade grade = memberGradeRepository.findAll().get(0);
        Long gradeId = grade.getId();

        Member m1 = memberRepository.save(
                Member.newSignup("test1@test.com", "hash", "Test1", null, gradeId)
        );
        MEMBER_1_ID = m1.getId();

        Member m2 = memberRepository.save(
                Member.newSignup("test2@test.com", "hash", "Test2", null, gradeId)
        );
        MEMBER_2_ID = m2.getId();
    }

    // ========== AC1: Earn with future available_at ==========

    @Test
    @DisplayName("AC1-1: 미래 사용 가능일로 적립하면 현재 잔액은 0")
    void earnScheduled_futureAvailableAt_currentBalanceIsZero() {
        // When
        pointService.earnScheduled(MEMBER_1_ID, new BigDecimal("1000"), 100L, NOW.plusDays(30), NOW.plusDays(180));

        // Then
        BigDecimal current = pointService.spendableBalance(MEMBER_1_ID, NOW);
        assertThat(current).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("AC1-2: availableAt 이후에는 잔액이 1000")
    void earnScheduled_futureAvailableAt_afterAvailableAtBalanceIs1000() {
        // When
        OffsetDateTime availableAt = NOW.plusDays(30);
        pointService.earnScheduled(MEMBER_1_ID, new BigDecimal("1000"), 100L, availableAt, NOW.plusDays(180));

        // Then
        BigDecimal after30Days = pointService.spendableBalance(MEMBER_1_ID, availableAt);
        assertThat(after30Days).isEqualByComparingTo("1000");
    }

    @Test
    @DisplayName("AC1-3: 현재 시점으로 적립하면 즉시 사용 가능")
    void earnScheduled_now_availableAt_immediatelySpendable() {
        // When
        pointService.earnScheduled(MEMBER_1_ID, new BigDecimal("1000"), 100L, NOW, NOW.plusDays(180));

        // Then
        BigDecimal balance = pointService.spendableBalance(MEMBER_1_ID, NOW);
        assertThat(balance).isEqualByComparingTo("1000");
    }

    // ========== AC2: Use with sufficient/insufficient balance ==========

    @Test
    @DisplayName("AC2-1: 잔액 700에서 500 사용 성공")
    void use_500From700_succeeds() {
        // Given: 잔액 700
        pointService.earnScheduled(MEMBER_1_ID, new BigDecimal("700"), null, NOW, NOW.plusDays(180));

        // When
        pointService.use(MEMBER_1_ID, new BigDecimal("500"), 100L);

        // Then: 현재 시점 기준으로 잔액 확인 (USE는 현재 시간에 생성됨)
        BigDecimal remaining = pointService.spendableBalance(MEMBER_1_ID, null);
        assertThat(remaining).isEqualByComparingTo("200");
    }

    @Test
    @DisplayName("AC2-2: 잔액 700에서 800 사용 시 INSUFFICIENT_POINTS 422")
    void use_800From700_throwsInsufficientPoints() {
        // Given: 잔액 700
        pointService.earnScheduled(MEMBER_1_ID, new BigDecimal("700"), null, NOW, NOW.plusDays(180));

        // When & Then
        assertThatThrownBy(() -> pointService.use(MEMBER_1_ID, new BigDecimal("800"), 100L))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).errorCode())
                        .isEqualTo(ErrorCode.INSUFFICIENT_POINTS));
    }

    @Test
    @DisplayName("AC2-3: 정확히 잔액만큼 사용 성공")
    void use_exactBalance_succeeds() {
        // Given: 잔액 500
        pointService.earnScheduled(MEMBER_1_ID, new BigDecimal("500"), null, NOW, NOW.plusDays(180));

        // When
        pointService.use(MEMBER_1_ID, new BigDecimal("500"), 100L);

        // Then: 현재 시점 기준으로 잔액 확인 (USE는 현재 시간에 생성됨)
        BigDecimal remaining = pointService.spendableBalance(MEMBER_1_ID, null);
        assertThat(remaining).isEqualByComparingTo("0");
    }

    // ========== AC3: Cancel order restores points ==========

    @Test
    @DisplayName("AC3-1: 주문 취소 시 적립과 사용이 모두 복구됨")
    void cancel_orderWithEarnAndUse_restoresBoth() {
        // Given: 주문에서 1000원 적립 후 200원 사용
        Long orderId = 100L;
        pointService.earnScheduled(MEMBER_1_ID, new BigDecimal("1000"), orderId, NOW, NOW.plusDays(180));
        assertThat(pointService.spendableBalance(MEMBER_1_ID, NOW)).isEqualByComparingTo("1000");

        pointService.use(MEMBER_1_ID, new BigDecimal("200"), orderId);
        // USE는 현재 시간에 생성되므로 현재 시점 기준으로 확인
        assertThat(pointService.spendableBalance(MEMBER_1_ID, null)).isEqualByComparingTo("800");

        // When: 주문 취소
        pointService.cancel(MEMBER_1_ID, orderId);

        // Then: 잔액이 원상복구 (1000)
        BigDecimal restored = pointService.spendableBalance(MEMBER_1_ID, NOW);
        assertThat(restored).isEqualByComparingTo("1000");
    }

    @Test
    @DisplayName("AC3-2: 취소할 내역이 없으면 아무일도 일어나지 않음 (멱등성)")
    void cancel_noHistory_noEffect() {
        // When & Then: 예외 없음
        pointService.cancel(MEMBER_1_ID, 999L);
    }

    @Test
    @DisplayName("AC3-3: 다른 회원의 내역은 취소 안 됨")
    void cancel_otherMembersHistory_noEffect() {
        // Given: 회원 1의 적립
        Long orderId = 100L;
        pointService.earnScheduled(MEMBER_1_ID, new BigDecimal("1000"), orderId, NOW, NOW.plusDays(180));

        // When: 회원 2가 취소 시도
        pointService.cancel(MEMBER_2_ID, orderId);

        // Then: 회원 1의 잔액 변화 없음
        assertThat(pointService.spendableBalance(MEMBER_1_ID, NOW)).isEqualByComparingTo("1000");
        assertThat(pointService.spendableBalance(MEMBER_2_ID, NOW)).isEqualByComparingTo("0");
    }

    // ========== AC4: Balance parity ==========

    @Test
    @DisplayName("AC4: 모든 연산 후 balance와 재계산 값이 일치")
    void allOperations_balanceMatchesRecompute() {
        // When: 복합 연산
        pointService.earnScheduled(MEMBER_1_ID, new BigDecimal("3000"), 1L, NOW, NOW.plusDays(180));
        pointService.use(MEMBER_1_ID, new BigDecimal("500"), 2L);
        pointService.earnScheduled(MEMBER_1_ID, new BigDecimal("1000"), 3L, NOW.plusDays(30), NOW.plusDays(180));
        pointService.use(MEMBER_1_ID, new BigDecimal("1000"), 4L);
        pointService.cancel(MEMBER_1_ID, 2L);

        // Then: 재계산 값 확인
        // EARN: 3000 (NOW에 사용 가능)
        // USE: 500 + 1000 = 1500 (현재 시간에 생성)
        // CANCEL: 500 (USE 500 취소, 현재 시간에 생성)
        // 총액: 3000 - 1500 + 500 = 2000
        BigDecimal recomputed = pointService.spendableBalance(MEMBER_1_ID, null);
        assertThat(recomputed).isEqualByComparingTo("2000");
    }

    // ========== AC5: Concurrent use ==========

    @Test
    @DisplayName("AC5: 동시 사용 10스레드 x 20원 = 정확히 5건 성공")
    void concurrentUse_10Threads20On100_exactly5Succeed() throws Exception {
        // Given: 잔액 100
        pointService.earnScheduled(MEMBER_1_ID, new BigDecimal("100"), null, NOW, NOW.plusDays(180));

        ExecutorService executor = Executors.newFixedThreadPool(10);
        CopyOnWriteArrayList<Boolean> results = new CopyOnWriteArrayList<>();

        // When: 10개 스레드가 동시에 20원씩 사용 시도
        List<CompletableFuture<Void>> futures = List.of(
                CompletableFuture.runAsync(() -> tryUse(results), executor),
                CompletableFuture.runAsync(() -> tryUse(results), executor),
                CompletableFuture.runAsync(() -> tryUse(results), executor),
                CompletableFuture.runAsync(() -> tryUse(results), executor),
                CompletableFuture.runAsync(() -> tryUse(results), executor),
                CompletableFuture.runAsync(() -> tryUse(results), executor),
                CompletableFuture.runAsync(() -> tryUse(results), executor),
                CompletableFuture.runAsync(() -> tryUse(results), executor),
                CompletableFuture.runAsync(() -> tryUse(results), executor),
                CompletableFuture.runAsync(() -> tryUse(results), executor)
        );

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(10, TimeUnit.SECONDS);
        executor.shutdown();

        // Then: 정확히 5건 성공, 5건 실패
        long successCount = results.stream().filter(b -> b).count();
        long failureCount = results.stream().filter(b -> !b).count();

        assertThat(successCount).isEqualTo(5);
        assertThat(failureCount).isEqualTo(5);

        // 최종 잔액 0 확인 (USE는 현재 시간에 생성되므로 null로 확인)
        BigDecimal finalBalance = pointService.spendableBalance(MEMBER_1_ID, null);
        assertThat(finalBalance).isEqualByComparingTo("0");
    }

    private void tryUse(CopyOnWriteArrayList<Boolean> results) {
        try {
            pointService.use(MEMBER_1_ID, new BigDecimal("20"), null);
            results.add(true);
        } catch (BusinessException e) {
            if (e.errorCode() == ErrorCode.INSUFFICIENT_POINTS) {
                results.add(false);
            } else {
                throw e;
            }
        }
    }

    // ========== Expire: 소멸 기능 ==========

    @Test
    @DisplayName("소멸: 만료된 적립금 소멸 처리")
    void expire_expiredEarns_createsExpireEntries() {
        // Given: 31일 전에 적립되고 1일 전에 만료된 포인트
        OffsetDateTime past = NOW.minusDays(31);
        OffsetDateTime expiresAt = NOW.minusDays(1);

        PointHistory earn = PointHistory.earn(MEMBER_1_ID, new BigDecimal("1000"), "테스트", null, past, expiresAt);
        pointHistoryRepository.save(earn);

        // When: 소멸 처리
        pointService.expire(MEMBER_1_ID, NOW);

        // Then: 잔액 0
        BigDecimal balance = pointService.spendableBalance(MEMBER_1_ID, NOW);
        assertThat(balance).isEqualByComparingTo("0");
    }

    // ========== Pending: 대기 중인 포인트 조회 ==========

    @Test
    @DisplayName("Pending: 30일 이내 사용 가능한 포인트 조회")
    void getPendingPoints_within30Days_returnsList() {
        // Given: 미래 사용 가능한 포인트
        pointService.earnScheduled(MEMBER_1_ID, new BigDecimal("1000"), 1L, NOW.plusDays(10), NOW.plusDays(180));
        pointService.earnScheduled(MEMBER_1_ID, new BigDecimal("500"), 2L, NOW.plusDays(25), NOW.plusDays(180));
        pointService.earnScheduled(MEMBER_1_ID, new BigDecimal("300"), 3L, NOW, NOW.plusDays(180)); // 즉시 사용 가능

        // When: NOW 기준으로 30일 이내 대기 포인트 조회
        List<PointHistory> pending = pointService.getPendingPoints(MEMBER_1_ID, 30, NOW);

        // Then: 2건 (즉시 사용 가능한 300원 제외)
        assertThat(pending).hasSize(2);
        assertThat(pending.stream().map(PointHistory::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("1500");
    }
}
