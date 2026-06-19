# Claims (append-only, UNTRUSTED — verified by adversarial Verify)

빌더가 슬라이스별로 주장. conductor가 빌더 요약에서 취합해 기록. Verifier가 클린 worktree에서 run-to-prove 재실행해 verdict 부여.

JAVA_HOME 필수: 모든 gradle 호출 앞에 `JAVA_HOME=/c/Users/a/.jdks/temurin-21/jdk-21.0.11+10`. Docker 데몬 UP 필요(Testcontainers).

---

## CLAIM M1-catalog-ranking
what: ProductPublicService가 product_review_summaries를 배치조회(findByProductIdIn)해 list/detail의 reviewCount/avgRating 실값화(N+1 제거). V18 마이그레이션으로 products.sales_count 추가; ProductRankingJob.recomputeAllRankings() 실계산(sales*0.5+review*0.3+rating*0.2, upsert, idempotent); SalesAggregationJob이 sales_count 갱신. POPULAR=sales_count DESC, RATING=avg_rating DESC NULLS LAST(id-fallback 제거). 신규 GET /api/products/rankings, /api/products/best-sellers(이미 permit된 /api/products/** 하위).
files: V18__products_sales_count.sql(new), Product.java(salesCount), ProductRepository.java(findAllIds/refreshAllSalesCounts), review/ProductReviewSummaryRepository.java(findByProductIdIn), ProductDtos.java(RankingItem), ProductPublicService.java, batch/ProductRankingJob.java, batch/SalesAggregationJob.java, public_api/ProductPublicController.java; tests ProductPublicApiIT(확장), batch/ProductRankingJobIT(new)
run-to-prove: `JAVA_HOME=/c/Users/a/.jdks/temurin-21/jdk-21.0.11+10 ./gradlew test --tests 'com.olive.commerce.product.ProductPublicApiIT' --tests 'com.olive.commerce.batch.ProductRankingJobIT' --tests 'com.olive.commerce.batch.BatchJobExecutionTest'`
expected: ProductRankingJobIT 4 그린(score 11.8000, idempotency, zero-data), ProductPublicApiIT 신규 ~8 그린(별점 실값, POPULAR/RATING 정렬, rankings/best-sellers)
risks: enrichRankings IN-list 크기, refreshAllSalesCounts 단일문 락, RATING LEFT JOIN 비용. ORDER BY는 enum switch라 SQL injection 무관.

## CLAIM M1-order-payment-integrity
what: Order에 payment_key(V17) + 승인시점 저장. PAID/PREPARING 취소가 pgClient.cancelPayment(저장키)로 실제 취소 + Payment行 CANCELED(멱등). CREATED/PAYMENT_PENDING 경로 불변. 쿠폰/포인트 보상 실패 비-swallow(=@Transactional 롤백, all-or-nothing). A4 confirm 순서: order→PAID+key, payment→APPROVED, APPROVE 트랜잭션로그 먼저 durable; inventory.commit은 마지막 best-effort(실패시 outbox INVENTORY_COMMIT_FAILED 기록, 재throw 안 함) → 기존 멱등가드와 합쳐 lost-approval/double-charge 제거.
files: V17__order_payment_key.sql(new), order/Order.java(paymentKey), payment/PaymentService.java, order/OrderService.java(PgClient 주입), tests order/OrderCancelApiIT(확장), payment/PaymentServiceTest(확장)
run-to-prove: `JAVA_HOME=/c/Users/a/.jdks/temurin-21/jdk-21.0.11+10 ./gradlew test --tests 'com.olive.commerce.order.OrderCancelApiIT' --tests 'com.olive.commerce.payment.PaymentServiceTest' --tests 'com.olive.commerce.payment.PaymentConfirmApiIT'`
expected: PAID 취소가 PG취소+Payment CANCELED(저장키), inventory 실패에도 승인 유지·재시도 confirmPayment 1회(중복결제 없음), 기존 confirm/idempotent 회귀 그린
risks(HIGH-severity 패널 대상): 취소 트랜잭션 내 PG 외부호출 후 후속단계 롤백 시 PG-DB 발산(real PG는 reconciliation 필요); A4 별도 트랜잭션 재시도에서 동일 멱등분기 발화 여부; 보상 롤백 후 재시도 double-restore 없는지; V17/V18 Flyway 순서

## CLAIM M1-refund-admin
what: RefundService 부분환불 비례계산(itemSubtotal - 비례할인 - 비례포인트, maxRefundable clamp, 잘못된 수량/항목 거부). Coupon/Inventory admin이 principal.memberId() 실제 adminId 전달. InventoryService.findByProductId null 차단(VALIDATION_FAILED). RefundRequestDto @Valid/@NotEmpty/@Min/@Size.
files: payment/RefundService.java, payment/RefundDtos.java, admin/CouponAdminController.java, admin/InventoryAdminController.java, inventory/InventoryService.java, order/OrderItem.java(setId 테스트헬퍼), tests payment/RefundServiceTest(확장), inventory/InventoryAdminListBoundTest(new)
[FOLLOW-UP 진행중] findByProductId(validId)가 빈 리스트 반환하던 회귀 → 실제 by-product 옵션재고 조회로 수정 중(빌더3 후속).
run-to-prove: `JAVA_HOME=/c/Users/a/.jdks/temurin-21/jdk-21.0.11+10 ./gradlew test --tests 'com.olive.commerce.payment.RefundServiceTest' --tests 'com.olive.commerce.inventory.InventoryAdminListBoundTest'`
expected: RefundServiceTest 부분/전액/과환불거부 그린; InventoryAdminListBoundTest null 예외 + validId 실재고 반환 그린
risks(committee 검토): 전액반품 시 배송비(3000) 미환불 — 비즈니스룰 확인 필요; HALF_UP 누적 라운딩; 중복 orderItemId; items @NotEmpty가 "빈=전액" 기존계약 깨는지 확인

## CLAIM M1-followups (conductor가 발견·지시한 결함 수정)
1. catalog list 쿼리 SQL 공백버그(text block 후행공백 제거→`ORDER BYp...`): ProductPublicService.buildListQuery 전체 concat 전환. 모든 SQL-concat 지점 감사(buildCountQuery/enrichRankings는 무사). → ProductPublicApiIT 10건 500→그린.
2. InventoryService.findByProductId(validId) 빈 리스트 회귀 → ProductOptionRepository로 옵션id 해석 후 InventoryRepository.findByProductOptionIdIn 조회. InventoryAdminListBoundTest를 IT로 전환(null 예외 + validId 실재고).
3. INVENTORY_COMMIT_FAILED 고아 이벤트 → OutboxEventDrainer.SUPPORTED_EVENT_TYPES에 추가 + InventoryCommitFailedEvent + InventoryCommitRetryListener(@EventListener, inventoryService.commit 멱등). InventoryCommitRetryIT(드레인→COMMITTED+DONE, 2회=no-op). oversell 구멍 차단.
4. (test fix) InventoryCommitRetryIT의 expires_at에 Instant 직접 바인딩→Timestamp.from() (Postgres JDBC 타입추론). 프로덕션 무관.

## M1 conductor smoke 결과
`./gradlew test`(JDK21+Docker) 스코프 9개 클래스 = **69 tests, 0 failed (GREEN)**.
tests: ProductPublicApiIT, ProductRankingJobIT, BatchJobExecutionTest, OrderCancelApiIT, PaymentServiceTest, PaymentConfirmApiIT, RefundServiceTest, InventoryAdminListBoundTest, InventoryCommitRetryIT.

