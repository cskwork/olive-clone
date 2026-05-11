package com.olive.commerce.promotion;

import com.olive.commerce.common.audit.AuditLogger;
import com.olive.commerce.common.persistence.PostgresIntegrationSupport;
import com.olive.commerce.promotion.Coupon.DiscountType;
import com.olive.commerce.promotion.CouponDtos.BulkIssueRequest;
import com.olive.commerce.promotion.CouponDtos.BulkIssueResponse;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace.NONE;

/**
 * OLV-051 Acceptance Criteria 검증:
 *  - Bulk issue: 1000 member ids + max_issue_count=500 → exactly 500 member_coupons rows
 *  - Concurrent issue calls do not exceed the cap
 */
@DataJpaTest
@Import(CouponService.class)
@AutoConfigureTestDatabase(replace = NONE)
class CouponServiceConcurrencyTest extends PostgresIntegrationSupport {

    @Autowired
    private CouponService couponService;

    @Autowired
    private CouponRepository couponRepository;

    @Autowired
    private MemberCouponRepository memberCouponRepository;

    @Autowired
    private EntityManager em;

    @MockBean
    private AuditLogger auditLogger;

    private List<Long> testMemberIds = new ArrayList<>();

    /**
     * 각 테스트 전에 테스트 회원 1000명을 생성합니다.
     * member_coupons 테이블의 FK 제약조건을 준수하기 위해 필요합니다.
     */
    @BeforeEach
    void setUpTestMembers() {
        // member_grades에서 BRONZE 등급 ID 조회
        Number gradeId = (Number) em.createNativeQuery(
                "SELECT id FROM member_grades WHERE name = 'BRONZE'"
        ).getSingleResult();

        // 테스트 회원 1000명 생성 (member_coupons FK 제약조건 준수)
        for (int i = 1; i <= 1000; i++) {
            Long memberId = ((Number) em.createNativeQuery("""
                    INSERT INTO members (email, password_hash, name, grade_id)
                    VALUES (:email, 'x', 'Test Member', :gradeId)
                    RETURNING id
                    """)
                    .setParameter("email", "test-member-" + i + "@example.com")
                    .setParameter("gradeId", gradeId)
                    .getSingleResult()).longValue();
            testMemberIds.add(memberId);
        }
        em.flush();
        em.clear();
    }

    @Test
    @WithMockUser(roles = {"PRODUCT_ADMIN"})
    void bulkIssue_respectsMaxIssueCount() {
        // given: max_issue_count=500인 쿠폰 생성
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        CouponDtos.AdminCreateRequest createRequest = new CouponDtos.AdminCreateRequest(
                "테스트 쿠폰",
                DiscountType.FIXED_AMOUNT,
                new BigDecimal("3000"),
                null,  // minOrderAmount
                now,
                now.plusDays(30),
                500    // maxIssueCount
        );
        var created = couponService.createCoupon(createRequest, null);
        Long couponId = created.id();

        // when: 1000명의 회원 ID로 동시 발급 요청
        BulkIssueRequest request = new BulkIssueRequest(testMemberIds);
        BulkIssueResponse result = couponService.bulkIssue(couponId, request, null);

        // then: 정확히 500건만 발급 성공
        assertThat(result.successCount()).isEqualTo(500);
        assertThat(result.failedCount()).isEqualTo(500);

        // 그리고 DB에 500건의 member_coupons만 존재
        List<MemberCoupon> issued = couponRepository.findAllMemberCoupons(couponId);
        assertThat(issued).hasSize(500);

        // 그리고 coupons.issued_count = 500
        Coupon coupon = couponRepository.findById(couponId).orElseThrow();
        assertThat(coupon.getIssuedCount()).isEqualTo(500);
    }

    @Test
    @WithMockUser(roles = {"PRODUCT_ADMIN"})
    void bulkIssue_concurrentRequests_doNotExceedMaxCount() throws Exception {
        // given: max_issue_count=10인 쿠폰
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        CouponDtos.AdminCreateRequest createRequest = new CouponDtos.AdminCreateRequest(
                "동시성 쿠폰",
                DiscountType.FIXED_AMOUNT,
                new BigDecimal("1000"),
                null,
                now,
                now.plusDays(30),
                10
        );
        var created = couponService.createCoupon(createRequest, null);
        Long couponId = created.id();

        // when: 각각 8명씩 요청하는 두 스레드가 동시 실행 (합계 16명 요청)
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch latch = new CountDownLatch(2);

        List<Integer> results = new CopyOnWriteArrayList<>();

        // 첫 번째 스레드: 회원 1-8명
        executor.submit(() -> {
            try {
                List<Long> members1 = testMemberIds.subList(0, 8);
                BulkIssueRequest req1 = new BulkIssueRequest(members1);
                var r1 = couponService.bulkIssue(couponId, req1, null);
                results.add(r1.successCount());
            } catch (Exception e) {
                e.printStackTrace();
                results.add(-1);  // 실패 시 -1 기록
            } finally {
                latch.countDown();
            }
        });

        // 두 번째 스레드: 회원 9-16명
        executor.submit(() -> {
            try {
                List<Long> members2 = testMemberIds.subList(8, 16);
                BulkIssueRequest req2 = new BulkIssueRequest(members2);
                var r2 = couponService.bulkIssue(couponId, req2, null);
                results.add(r2.successCount());
            } catch (Exception e) {
                e.printStackTrace();
                results.add(-1);  // 실패 시 -1 기록
            } finally {
                latch.countDown();
            }
        });

        latch.await(10, TimeUnit.SECONDS);
        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);

        // then: 두 결과의 합이 10 이하 (max_issue_count)
        assertThat(results).hasSize(2);
        assertThat(results.get(0) + results.get(1)).isLessThanOrEqualTo(10);

        // 그리고 DB의 issued_count도 10 이하
        Coupon coupon = couponRepository.findById(couponId).orElseThrow();
        assertThat(coupon.getIssuedCount()).isLessThanOrEqualTo(10);
    }

    @Test
    @WithMockUser(roles = {"PRODUCT_ADMIN"})
    void bulkIssue_skipsAlreadyIssuedMembers() {
        // given: 쿠폰 생성
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        CouponDtos.AdminCreateRequest createRequest = new CouponDtos.AdminCreateRequest(
                "중복 발급 테스트 쿠폰",
                DiscountType.FIXED_AMOUNT,
                new BigDecimal("1000"),
                null,
                now,
                now.plusDays(30),
                100
        );
        var created = couponService.createCoupon(createRequest, null);
        Long couponId = created.id();

        // when: 같은 회원 목록으로 두 번 발급
        List<Long> memberIds = testMemberIds.subList(0, 3);
        BulkIssueRequest request = new BulkIssueRequest(memberIds);

        var result1 = couponService.bulkIssue(couponId, request, null);
        var result2 = couponService.bulkIssue(couponId, request, null);

        // then: 첫 번째는 3건 성공
        assertThat(result1.successCount()).isEqualTo(3);

        // 그리고 두 번째는 0건 성공 (이미 발급됨)
        assertThat(result2.successCount()).isEqualTo(0);
        assertThat(result2.failedMemberIds()).containsExactly(memberIds.get(0), memberIds.get(1), memberIds.get(2));

        // 그리고 DB에도 3건만 존재
        List<MemberCoupon> issued = couponRepository.findAllMemberCoupons(couponId);
        assertThat(issued).hasSize(3);
    }
}
