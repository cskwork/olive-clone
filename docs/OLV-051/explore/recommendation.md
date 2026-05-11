# OLV-051 Recommendation

## 선택: 옵션 1 — 단일 CouponService + Admin/User 컨트롤러 분리

**이유**:
1. **OLV-061 의존관계 명확**: `CouponService.validate()`, `tryReserve()`, `markUsed()`, `restore()`가 한 곳에 모여 있어 주문 서비스에서 호출하기 쉬움
2. **티켓 범위 적합**: Admin CRUD + 발급 + User 목록 + 검증이 모두 쿠폰 도메인의 핵심 로직이므로 분리할 이유가 적음
3. **확장 가능**: 향후 쿠폰 종류가 늘어나면 `Coupon`의 하위 엔티티 또는 전략 패턴으로 리팩토링 가능

## First Failing Test: Bulk Issue 동시성 제어

**왜 이 테스트인가?**
- AC에서 명시된 가장 까다로운 요구사항: "1000 member ids + max_issue_count=500 → exactly 500 member_coupons rows"
- 동시성 제어 없이 구현하면 이 테스트가 실패하므로 `SELECT FOR UPDATE` 패턴을 강제함

```java
// src/test/java/com/olive/commerce/promotion/CouponServiceConcurrencyTest.java

@DataJpaTest
@Import(CouponService.class)
class CouponServiceConcurrencyTest extends PostgresIntegrationSupport {

    @Autowired
    private CouponService couponService;

    @Autowired
    private CouponRepository couponRepository;

    @Test
    @DisplayName("대량 발급: 1000명 요청 + max_issue_count=500 → 정확히 500건만 발급")
    void bulkIssue_respectsMaxIssueCount() {
        // given: max_issue_count=500인 쿠폰 생성
        Long couponId = couponRepository.save(Coupon.create(
            "테스트 쿠폰",
            DiscountType.FIXED_AMOUNT,
            new BigDecimal("3000"),
            null,  // minOrderAmount
            LocalDateTime.now(),
            LocalDateTime.now().plusDays(30),
            500    // maxIssueCount
        )).getId();

        // when: 1000명의 회원 ID로 동시 발급 요청
        List<Long> memberIds = LongStream.range(1, 1001).boxed().toList();
        CouponService.BulkIssueResult result = couponService.bulkIssue(couponId, memberIds, null);

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
    @DisplayName("동시성: 두 스레드가 동시에 발급해도 max_issue_count 초과하지 않음")
    void bulkIssue_concurrentRequests_doNotExceedMaxCount() throws Exception {
        // given: max_issue_count=10인 쿠폰
        Long couponId = couponRepository.save(Coupon.create(
            "동시성 쿠폰",
            DiscountType.FIXED_AMOUNT,
            new BigDecimal("1000"),
            null,
            LocalDateTime.now(),
            LocalDateTime.now().plusDays(30),
            10
        )).getId();

        // when: 각각 8명씩 요청하는 두 스레드가 동시 실행 (합계 16명 요청)
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch latch = new CountDownLatch(2);

        List<Integer> results = new CopyOnWriteArrayList<>();

        executor.submit(() -> {
            try {
                List<Long> members1 = LongStream.range(1, 9).boxed().toList();
                var r1 = couponService.bulkIssue(couponId, members1, null);
                results.add(r1.successCount());
            } finally {
                latch.countDown();
            }
        });

        executor.submit(() -> {
            try {
                List<Long> members2 = LongStream.range(9, 17).boxed().toList();
                var r2 = couponService.bulkIssue(couponId, members2, null);
                results.add(r2.successCount());
            } finally {
                latch.countDown();
            }
        });

        latch.await(5, TimeUnit.SECONDS);
        executor.shutdown();

        // then: 두 결과의 합이 10 이하 (max_issue_count)
        assertThat(results.get(0) + results.get(1)).isLessThanOrEqualTo(10);

        // 그리고 DB의 issued_count도 10 이하
        Coupon coupon = couponRepository.findById(couponId).orElseThrow();
        assertThat(coupon.getIssuedCount()).isLessThanOrEqualTo(10);
    }
}
```

## 구현 순서

1. **Entity & Repository**: `Coupon`, `MemberCoupon`, JPA Repositories
2. **DTOs**: `CouponDtos.java` (AdminCreateRequest, AdminResponse, IssueRequest, IssueResponse, MemberCouponResponse)
3. **Service — bulkIssue 먼저**: 위 테스트를 통과하는 `CouponService.bulkIssue()`
   - `@Lock(LockModeType.PESSIMISTIC_WRITE)` 사용
   - 트랜잭션 내에서 `issued_count` 체크 + 증가
4. **Service — 나머지 메서드**: `validate()`, `tryReserve()`, `markUsed()`, `restore()`
5. **Admin Controller**: `POST /api/admin/coupons`, `GET /api/admin/coupons`, `PATCH /api/admin/coupons/{id}/status`, `POST /api/admin/coupons/{id}/issue`
6. **User Controller**: `GET /api/me/coupons?status=ISSUED`
7. **Integration Tests**: 각 API endpoint에 대한 `MockMvc` 테스트

## 추가 ErrorCode

```java
// ErrorCode.java 에 추가
COUPON_NOT_FOUND(HttpStatus.NOT_FOUND),
COUPON_EXPIRED(HttpStatus.BAD_REQUEST),
COUPON_ALREADY_ISSUED(HttpStatus.CONFLICT),
COUPON_ISSUE_LIMIT_EXCEEDED(HttpStatus.CONFLICT),
COUPON_USAGE_LIMIT_EXCEEDED(HttpStatus.CONFLICT),
```

## 감사 로그 이벤트

```java
auditLogger.log("ADMIN_MUTATION", Map.of(
    "adminId", adminId,
    "action", "BULK_ISSUE_COUPON",
    "couponId", couponId,
    "requestedCount", memberIds.size(),
    "successCount", result.successCount(),
    "failedCount", result.failedCount()
));
```
