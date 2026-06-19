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

---

## CLAIM M2-config-edge-security
what: bucket4j(com.bucket4j:bucket4j_jdk17-core:8.14.0) per-IP in-memory RateLimitFilter — auth 20/min, browse(/api/products,/api/search) 300/min, remoteAddr 기반(X-Forwarded-For 미신뢰), OPTIONS 제외, 초과 시 429 ApiResponse. **test 프로필에서 코드 바이패스**(environment.matchesProfiles("test")) → 기존 스위트 영향 없음(yaml 불가 환경 대응). webhook HMAC 비밀 외부화(@Value olive.pg.webhook-secret, 기본값 fallback) — MockPgClient 하드코딩 제거. SecurityConfig에 CORS allowlist(olive.cors.allowed-origins, 기본 localhost:5173/8080, allowCredentials). MockPgController @Profile("!prod")+@PreAuthorize(SUPER_ADMIN). B4 prod config는 yaml 쓰기 불가 → Java ServerTuningConfig(@Profile("!test")): gzip 압축, graceful shutdown(30s), HikariCP pool(max30/min10/timeouts).
files: build.gradle.kts(bucket4j), common/web/RateLimitProperties.java(new), common/web/RateLimitFilter.java(new), common/config/SecurityConfig.java(CORS+필터등록), common/config/ServerTuningConfig.java(new), payment/client/MockPgClient.java, payment/config/PgClientConfig.java, payment/test/MockPgController.java; tests common/web/RateLimitFilterTest(new,unit), common/web/RateLimitAndCorsIT(CORS), 
[conductor 수정] bucket4j 좌표 8.10.1→8.14.0; matchesProfiles(Profiles) API오용→matchesProfiles("test") (프로덕션+테스트 스텁).
run-to-prove: `JAVA_HOME=/c/Users/a/.jdks/temurin-21/jdk-21.0.11+10 ./gradlew test --tests 'com.olive.commerce.common.web.RateLimitFilterTest' --tests 'com.olive.commerce.common.web.RateLimitAndCorsIT' --tests 'com.olive.commerce.common.security.SecurityFilterChainIT' --tests 'com.olive.commerce.payment.PaymentWebhookTest'`
expected: rate-limit 429/바이패스, CORS preflight, webhook HMAC 정상, 보안체인 회귀 없음
risks(committee): yaml 미적용분(올바른 prod 비밀/CORS origin은 운영 배포 시 env로) — Not covered 명시 필요; remoteAddr 기반이라 LB 뒤에서는 공유버킷; in-memory라 멀티인스턴스 비분산(future Redis); ServerTuningConfig lifecycleProcessor 빈 오버라이드.

## CLAIM M2-error-dlq
what: 에러 노출 차단 — JobAdminController/DeliveryAdminController가 e.getMessage() 대신 제너릭 메시지 반환(상세는 서버로그). GlobalExceptionHandler.handleBusiness가 safeClientMessage(ErrorCode) 전수 switch로 내부 id 없는 안전 메시지 반환(서버로그엔 full). Outbox DLQ 운영: OutboxEvent.requeueFromDlq(), repo countByDlqTrue/findAllDlq, CommerceMetrics outbox_dlq_count 게이지, OutboxAdminController(GET /api/admin/outbox/dlq, POST .../requeue, .../requeue-all, SUPER_ADMIN).
files: common/error/GlobalExceptionHandler.java, batch/JobAdminController.java, admin/DeliveryAdminController.java, search/OutboxEvent.java(+repo), common/metrics/CommerceMetrics.java, admin/OutboxAdminController.java(new); tests common/error/GlobalExceptionHandlerTest(확장), admin/OutboxAdminApiIT(new), public_api/SearchApiIT(메시지 단언 갱신)
run-to-prove: `JAVA_HOME=/c/Users/a/.jdks/temurin-21/jdk-21.0.11+10 ./gradlew test --tests 'com.olive.commerce.common.error.GlobalExceptionHandlerTest' --tests 'com.olive.commerce.admin.OutboxAdminApiIT'`
expected: 404 본문에 내부 id 없음·제너릭, DLQ requeue가 PENDING 전이+게이지 반영
risks(committee): generic 메시지가 유용정보 과도제거(클라는 error.code로 분기 권장); DLQ 게이지가 admin 호출시에만 갱신(스케줄러 미연결 — staleness); findAllDlq 무페이지네이션.

---

## CLAIM M3-wishlist
what: 신규 wishlist 모듈(cart 패턴 미러). wishlist_items(member_id, product_id, UNIQUE(member_id,product_id), created_at) V19. WishlistService.add(멱등, 상품존재검증→PRODUCT_NOT_FOUND)/remove/list(배치 product 조회로 N+1 회피). WishlistController /api/me/wishlist GET(페이지)/POST/DELETE{productId}, @AuthenticationPrincipal. 인증 경로(USER)라 SecurityConfig 불변.
files: wishlist/WishlistItem.java, WishlistItemRepository.java, WishlistDtos.java, WishlistService.java, WishlistController.java (all new), V19__wishlist.sql; test wishlist/WishlistApiIT(new, 5 ACs)
run-to-prove: `JAVA_HOME=/c/Users/a/.jdks/temurin-21/jdk-21.0.11+10 ./gradlew test --tests 'com.olive.commerce.wishlist.WishlistApiIT'`
expected: add→list 노출, 중복add 멱등(1건), remove, 없는상품 404, per-member 격리 — 5 그린
risks(committee): 동시 double-add는 app-check 통과 후 DB UNIQUE에 의존(DataIntegrityViolation→500 가능, 프로덕션은 catch 권장); 네이티브 IN-list product 조회

## CLAIM M3-mypage-subresources
what: C2(/api/me/summary)는 **이미 구현돼 있음**(MemberProfileController.summary: pointService.spendableBalance + memberCoupons.countByMemberIdAndStatus("ISSUED") + orders.countByMemberId + grade) — 검증만. C3 신규: GET /api/categories/{id}/products, GET /api/brands/{id}/products → 기존 productPublicService.list(categoryId/brandId 필터) 재사용, 존재검증(CATEGORY/BRAND_NOT_FOUND). SecurityConfig에 GET /api/categories/*/products, /api/brands/*/products permit 2줄 추가(catch-all 위).
files: public_api/CategoryPublicController.java, public_api/BrandPublicController.java, common/config/SecurityConfig.java(permit 2); test public_api/SubResourceProductApiIT(new, 8)
run-to-prove: `JAVA_HOME=/c/Users/a/.jdks/temurin-21/jdk-21.0.11+10 ./gradlew test --tests 'com.olive.commerce.public_api.SubResourceProductApiIT' --tests 'com.olive.commerce.member.MemberProfileApiIT'`
expected: 카테고리/브랜드 하위리소스 목록·페이지·404·무인증 + 마이페이지요약 — 그린
risks(committee): permit 순서(catch-all 앞), 캐시 stale, N+1(기존 list 재사용이라 신규 없음)

---

## CLAIM M6-order-refactor (behavior-preserving)
what: OrderService(1009 LOC) → facade + OrderCreationService(8-step 생성)/OrderCancellationService(취소+M1 보상 verbatim)/OrderQueryService(목록/상세/admin상태). public API·@Transactional 경계·이벤트 발행 불변(코드 이동만). 죽은 중복 제거: delivery/CarrierClient.java, delivery/MockCarrierClient.java(루트), order/MemberAddress.java(스텁) — grep 근거로 미참조 확인 후 git rm.
files: order/OrderService.java(facade), order/OrderCreationService.java(new), order/OrderCancellationService.java(new), order/OrderQueryService.java(new); deleted delivery/CarrierClient.java, delivery/MockCarrierClient.java, order/MemberAddress.java
run-to-prove: `JAVA_HOME=/c/Users/a/.jdks/temurin-21/jdk-21.0.11+10 ./gradlew test --tests 'com.olive.commerce.order.*' --tests 'com.olive.commerce.payment.*' --tests 'com.olive.commerce.delivery.*' --tests 'com.olive.commerce.inventory.*' --tests 'com.olive.commerce.e2e.PurchaseFlowE2ETest'`
expected: 전 order/payment/delivery/inventory/e2e 그린(동작 보존) — conductor smoke GREEN(3m)
risks(committee): 트랜잭션 propagation(facade 미주석, 경계는 delegate), M1 취소-보상 순서, 이벤트 source 타입 변경(OrderCancellationService) 리스너 영향 없음 확인

## CLAIM FE-A-foundation (frontend)
what: 세션/리프레시 — api.ts에 refreshToken 저장 + 401 시 POST /api/auth/refresh 1회 재시도(isRefreshing 루프가드)+토큰 클리어. cart.ts mergeAnonymousCart, Login에서 로그인 후 호출. 신규 lib: search.ts/wishlist.ts/mypage.ts + orders.listMyOrders + types 보강. index.html Pretendard CDN. base.css: skeleton-shimmer 공통화, .empty-state/.error-state, prefers-reduced-motion. App.tsx: /search,/category/:id,/mypage,/orders,/wishlist 라우트 + 실제 NotFound 404, /dev는 import.meta.env.DEV 가드. 플레이스홀더 6페이지(Search/ProductList/MyPage/OrderHistory/Wishlist/NotFound).
files: frontend/src/lib/{api,cart,orders,types,search(new),wishlist(new),mypage(new)}.ts, index.html, styles/base.css, App.tsx, pages/auth/Login.tsx, pages/{Search,ProductList,MyPage,OrderHistory,Wishlist,NotFound}.tsx(+css); [conductor 수정] package.json @types/node 추가 — 기존 `tsc --noEmit` node:url 실패(빌드 breakage) 해소
run-to-prove: `cd frontend && npm run build` (tsc --noEmit && vite build)
expected: 빌드 성공(136 modules), 신규 라우트 해석, 플레이스홀더 렌더
risks(committee): 동시 401 burst 시 다중 refresh(MVP 허용), CartMergeResponse 필드 추정, refresh 루프가드

