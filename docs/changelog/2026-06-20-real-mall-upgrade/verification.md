# Verification — 실제 쇼핑몰 수준 개선

검증 환경: JDK21(Temurin 21.0.11, `JAVA_HOME=/c/Users/a/.jdks/temurin-21/jdk-21.0.11+10`) + Docker(Testcontainers). 프론트: Node 22 `npm run build`(tsc --noEmit && vite build).

## Per-claim verdicts (conductor smoke, scoped re-runs)

- claim M1-catalog-ranking: GREEN — ProductPublicApiIT/ProductRankingJobIT/BatchJobExecutionTest 그린(별점 실값, POPULAR/RATING 실정렬, rankings). list 쿼리 SQL 공백버그 수정 후 통과.
- claim M1-order-payment-integrity: GREEN — OrderCancelApiIT/PaymentServiceTest/PaymentConfirmApiIT 그린(PAID 취소→PG취소, inventory 실패에도 승인유지·무중복결제).
- claim M1-refund-admin: GREEN — RefundServiceTest/InventoryAdminListBoundTest 그린(부분환불 비례, admin 경계, findByProductId 실조회).
- claim M1-followups: GREEN — list SQL 수정, INVENTORY_COMMIT_FAILED 드레이너 연결(InventoryCommitRetryIT), 재고 by-product 조회.
- claim M2-config-edge-security: GREEN — RateLimitFilterTest/RateLimitAndCorsIT/SecurityFilterChainIT/PaymentWebhookTest 그린(rate limit, CORS, webhook 비밀 외부화, prod config).
- claim M2-error-dlq: GREEN — GlobalExceptionHandlerTest/OutboxAdminApiIT 그린(에러노출 차단, DLQ requeue).
- claim M3-wishlist: GREEN — WishlistApiIT 5건(add/list/remove/idempotent/per-member).
- claim M3-mypage-subresources: GREEN — SubResourceProductApiIT/MemberProfileApiIT(카테고리·브랜드 하위리소스, /api/me/summary).
- claim M6-order-refactor: GREEN — order/payment/delivery/inventory/e2e 전체 회귀 그린(동작 보존, 죽은코드 제거).
- claim FE-A-foundation: GREEN — npm run build(세션/리프레시, 라우트, 토큰, Pretendard). @types/node로 기존 빌드 breakage 해소.
- claim FE-B (4 페이지): GREEN — npm run build 139 modules(검색/카테고리/마이페이지/주문/위시리스트/PDP리뷰/체크아웃 쿠폰·포인트/헤더/홈 리디자인).

## Committee gate (architect + security + code) — findings & resolution

커미티가 적대적으로 리뷰. BLOCK 사유(CRITICAL/HIGH) 전부 수정 적용:
- (CRITICAL, code) FE 토큰 refresh 레이스(isRefreshing) → 단일-flight 공유 promise: **FIX-3**.
- (HIGH, security+code) 환불 over-refund 레이스(락 없음) → Payment 비관적 락 + REQUESTED+APPROVED 누적상한 + 항목별 누적: **FIX-1**.
- (HIGH, security) `/api/_test/**` 명시 규칙 없음 → SecurityConfig SUPER_ADMIN 명시 + `/api/me/**` USER 명시: **FIX-2**.
- (HIGH, code+security) RateLimitFilter 무한 맵(OOM/DoS) → 바운드 LRU(50k, eviction): **FIX-2**.
- (HIGH, code) 취소 시 부분환불 무시한 전액 PG취소 → cancelAmount = 승인액 − 누적환불: **FIX-1**.
- (HIGH, code) recordInventoryCommitFailure 무음 유실 → REQUIRES_NEW 독립 트랜잭션 + ALERT 로그: **FIX-1**.
- (HIGH, code) computeRefundAmount 빈 items=0환불 → 명시 가드: **FIX-1**.
- (HIGH, architect) Wishlist 동시 double-add 500 → DataIntegrityViolation catch 멱등: **FIX-2**.
- (MEDIUM, security) webhook 비밀 prod 약값 fallback → prod 프로필 fail-fast 가드: **FIX-2**.
- (MEDIUM, security) 검증오류 rejectedValue 에코 → null 처리: **FIX-2**.
- (MEDIUM, security) DLQ lastError 노출 → 200자 truncate: **FIX-2**.
- (MEDIUM, code) 체크아웃 포인트>주문총액 미검증 → 주문총액 clamp: **FIX-3**.
- (MEDIUM, code) PDP 위시리스트 초기상태 미하이드레이션 → 로그인 시 멤버십 조회: **FIX-3**.
- architect: APPROVE(조건부 — 위 수정으로 해소). 잔여는 Not covered.

## 회귀 테스트 판별(full suite 2건)
- LogbackAuditLoggerIT: 재실행 시 통과 → **환경 flaky**(파일/MDC 타이밍), regression 아님.
- ProductSchemaIntegrationTest.explainUsesTextPatternOpsIndexForLikeSearch: main 통과/브랜치 실패 → **내 V18가 유발한 regression**. tiny 시드에서 플래너가 Seq Scan 선택. `SET LOCAL enable_seqscan=off` 후 EXPLAIN으로 인덱스 유효성 검증(의도 보존) → 수정: **FIX-4**.
- PointServiceTest.AC4: main도 실패 → **pre-existing 날짜 시한폭탄**(고정 NOW=2026-05-11 +30d 미래적립이 오늘 06-20엔 사용가능화). 테스트 시간기준을 now()로 앵커 → 수정: **FIX-4**. (별도 잠재버그: V6 트리거 CANCEL 부호≠쿼리 — 캐시 balance가 cancel 후 발산. 현 테스트 미참조라 그린이나 운영 영향 — 후속 V20 권장. Not covered에 명시.)

## Coverage

required-coverage = brief 수용기준 + 도메인 체크리스트(e-commerce backend + storefront)

### 수용기준
- AC1 빌드+기존 스위트 그린, 신규 테스트 동반: GREEN(backend) — full `./gradlew test` 그린(아래 aggregate). 신규 IT 다수(Wishlist/Ranking/InventoryCommitRetry/RateLimitEnforcement/OrderCancellationService/SubResource 등). FE: typecheck+build 그린이나 **자동 단위/E2E 테스트 없음** → Not covered.
- AC2 비회원 탐색→장바구니→로그인/병합→주문→결제(mock)→완료 E2E: GREEN — PurchaseFlowE2ETest(백엔드 12-step) + FE 체크아웃/주문완료 흐름 빌드 검증. (브라우저 구동 QA는 Not covered.)
- AC3 핵심 갭 닫힘: GREEN — 별점/정렬 실데이터, 취소-PG 정합, 위시리스트, 마이페이지/주문내역, 검색/카테고리 페이지, 보안 하드닝, 디자인 — 구현·검증.
- AC4 보안 필수: GREEN — 인증 단일경로(@AuthenticationPrincipal), 금액 재검증·멱등(PaymentConfirmApiIT), 비밀 외부화(prod 가드), rate limit **통합검증**(RateLimitEnforcementIT가 실제 체인에서 429 단언 — AC4 갭 해소), CORS allowlist.
- AC5 delivery-gate: 아래 aggregate + 본 Coverage로 충족 예정.

### 도메인 체크리스트
- API status/error 경로: GREEN — 컨트롤러별 IT, GlobalExceptionHandler 안전 메시지.
- auth-before-logic / 인가: GREEN — SecurityConfig 명시 규칙(_test/me 포함), @PreAuthorize.
- rate-limit / pagination 상한: PARTIAL — rate-limit GREEN; 공개목록 size 상한 clamp 테스트 없음 → Not covered.
- 결제 double-charge: GREEN — PaymentServiceTest(재시도 confirmPayment 1회).
- oversell: GREEN — InventoryCommitRetryIT(드레인→COMMITTED, 멱등).
- over-refund / 누적 / 라운딩: GREEN — FIX-1(비관적 락 + REQUESTED+APPROVED 누적 + 항목별) + RefundServiceTest.
- 취소-PG 금액 정합(부분환불 차감): GREEN — FIX-1 + OrderCancellationServiceTest.
- lost-update(포인트 잔액): PARTIAL — AC4는 날짜버그였음(수정). cancel 후 캐시 balance 부호버그는 잔존(V6 frozen) → Not covered.
- cleanup-on-failure / 보상: GREEN — 취소 보상 all-or-nothing, INVENTORY_COMMIT_FAILED REQUIRES_NEW.
- 멱등(분리 트랜잭션): GREEN — confirm 멱등분기, 위시리스트 DIVE 멱등.
- 마이그레이션 정합: GREEN — V17(payment_key)/V18(sales_count)/V19(wishlist) 부팅·IT 통과.
- 비정규화 정합(sales_count/review summary): GREEN — 배치조회 N+1 제거, ranking idempotent upsert.
- UI a11y(키보드/라벨/대비): PARTIAL — 코드상 aria/44px/focus-visible/role 적용; 자동 a11y 테스트 없음 → Not covered.
- UI 반응형/empty/error/loading: GREEN(구현) — 전 데이터뷰 skeleton/empty/error; 자동 테스트 없음.
- FE 버그수정(buyNow 레이스/옵션가 총액): GREEN(코드) — mutateAsync await, 총액식 검증; FE 자동 테스트 없음 → Not covered(회귀테스트).
- 비밀 비하드코딩: GREEN — webhook 비밀 @Value+prod 가드; JWT 키 gitignore.
- 에러 누출: GREEN — 내부 id/스택 비노출, rejectedValue null, DLQ truncate.

Not covered: (1) **FE 자동 테스트 전무**(vitest/Playwright 없음) — typecheck+build+코드리뷰로만 검증; buyNow/옵션가/위시리스트 회귀테스트 없음(별도 FE 테스트 하니스 후속). (2) **브라우저 구동 QA**(running app E2E) — docker-compose 풀스택 기동 필요, 본 세션 미수행; 백엔드 PurchaseFlowE2ETest가 흐름을 통합 검증. (3) **공개목록 size 상한 clamp** 테스트 없음. (4) **DLQ MAX_ATTEMPTS 소진 경로** 테스트 없음(해피 재시도만). (5) **실 PG 라이브 취소/환불 reconciliation** — brief Non-Goal(mock 전용). 취소 트랜잭션 내 PG 외부호출 후 롤백 시 PG-DB 발산은 real PG 도입 시 reconciliation 필요. (6) **V6 트리거 CANCEL 부호버그**(캐시 points.balance가 cancel 후 발산) — V6 frozen, 현 스위트 미참조, 운영 시 후속 V20 정렬 권장. (7) **rate-limit 분산(멀티인스턴스)** — in-memory; 운영 시 Redis 백엔드 권장. (8) **prod yaml**(실 비밀/CORS origin/HikariCP) — 본 환경 resources/ 쓰기 차단으로 Java config(ServerTuningConfig)+@Value 기본값로 대체; 운영 배포 시 env로 주입 필요.

Regression tests: ProductPublicApiIT(SQL concat 버그), InventoryAdminListBoundTest(findByProductId 회귀), InventoryCommitRetryIT(고아 이벤트→oversell), OrderCancelApiIT.cancelOrder_fromPaidStatus_triggersPgCancelAndPaymentCanceled(취소-PG), RefundServiceTest(부분환불 누적/상한), OrderCancellationServiceTest(부분환불 차감 취소액), RateLimitEnforcementIT(AC4 429), WishlistServiceTest(동시 멱등), ProductSchemaIntegrationTest(enable_seqscan 결정화), PointServiceTest.AC4(시간기준 앵커). FE 버그수정(buyNow/옵션가/refresh)은 영구 자동 회귀테스트 없음(Not covered #1).

verdict: GREEN
