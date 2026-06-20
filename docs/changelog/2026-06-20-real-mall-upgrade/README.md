# Run: 실제 쇼핑몰 수준 개선 (real-mall-upgrade)

- **Date:** 2026-06-20
- **Mode:** LEGACY (improve/refactor + feature add on existing codebase)
- **Objective:** 기존 olive-clone(올리브영형 H&B 커머스 백엔드 + Vite SPA)을 "실제 쇼핑몰로 사용할 수 있는 수준"으로 개선. 코드 구조 전면 개편 허용. 필요 시 superdesign으로 디자인 개선.
- **Orchestration:** ultracode(Workflow 멀티에이전트)로 Build 구현.

## Priority Rules (advisory — production e-commerce backend + storefront)

1. 돈/재고/주문 불변식은 DB 제약 + 트랜잭션으로 강제. 애플리케이션 단 검증만으로 신뢰하지 않는다.
2. 결제는 멱등(idempotency key) + 웹훅 서명검증 + 금액 재검증. 클라이언트 금액을 신뢰하지 않는다.
3. 인증/인가는 단일 경로로 통일(@AuthenticationPrincipal). memberId 하드코딩/문자열 파싱 금지.
4. 모든 외부경계 입력은 스키마 검증(@Valid + DTO 제약) 후 처리. 신뢰 경계에서 fail-fast.
5. 공개 엔드포인트는 rate limit + 페이지네이션 상한. 무한 쿼리 금지.
6. 변경은 최소 blast radius. 기존 스타일/구조 유지, 무관한 리팩터/리네임 금지(단 사용자가 전면개편 허용 — 명시 슬라이스로만).
7. 비밀정보 하드코딩 금지. 로컬 자격증명은 secret/env로.
8. 비동기 부작용은 outbox-first + 재시도 + DLQ. source-of-truth 유실 금지.
9. 신규/수정 코드는 테스트 동반(unit/integration). 빌드+기존 스위트 그린 유지.
10. UI는 디자인 토큰 기반(green=brand/CTA, red=price/discount 역할 고정), 접근성(44px 터치, aria) 준수.

## Phase Log

- 2026-06-20 Intake: vault 생성. 기존 plan input(`docs/OLIVE_CLONE_PLAN_INPUT.md`, 2026-05-29)을 grounding 입력으로 채택. 단 3주 경과로 일부 stale → Explore에서 실제 현재 상태 재확인.
- (진행 중) Explore: 백엔드/프론트/보안운영/테스트/디자인 5개 축 병렬 매핑.

## Codebase Map (Explore, 2026-06-20)

기존 plan input(2026-05-29)의 Phase 0 부채는 대부분 이미 해소됨을 확인:
- dead `com.oliveyoung.oliveyoung` 트리 / `public_deprecated` 패키지: **이미 제거됨**(존재하지 않음).
- JWT principal 추출: **일관화 완료**(@AuthenticationPrincipal, `1L` fallback 0건). PointController/MemberCouponController도 `memberId()` 사용.
- god class 분리: 부분 진행 — OrderService 1009 LOC(여전히 최대), OrderPricingCalculator/CartMergeService/PaymentTransactionRecorder 추출됨.
- OpenAPI/Swagger, DomainProperties: 추가됨. SPA(React/Vite, TanStack Query): 존재(약 40% 표면).

### A. 백엔드 정합성/완성도 (file:line)
- 별점 0 하드코딩: `ProductDtos.java:218`, `ProductPublicService.java:367` → 리뷰 있어도 모든 상품 0점 표시. (product_review_summaries 테이블엔 avg_rating/review_count 존재, JOIN 안 함)
- POPULAR/RATING 정렬이 `id DESC`로 fallback: `ProductPublicService.java:190,194`.
- 주문취소 시 PG 취소 미호출: `OrderService.java:570` (TODO). Order 엔티티에 paymentKey 필드 없음 → 환불 불가.
- 결제: inventory.commit() 실패 시 PG 승인까지 롤백 → 재시도 중복결제 위험: `PaymentService.java:229-233`. (단 confirm 멱등/금액 재검증/webhook 서명검증/중복제거는 양호)
- ProductRankingJob.recomputeAllRankings() no-op 스텁: `ProductRankingJob.java:60-73`.
- 부분환불 미구현(전액만): `RefundService.java:106`.
- Wishlist 도메인 전무(엔티티/서비스/API/마이그레이션 0).
- /api/me 빈약(포인트/쿠폰/주문수 없음); `/api/categories/{id}/products`, `/api/brands/{id}/products` 없음; 검색 facet 필터 없음.
- admin adminId null: `CouponAdminController.java:39,69,87`, InventoryAdminController. InventoryService.findByProductId(null)→findAll() 무한.
- Outbox DLQ: 5회 후 폐기, requeue/alert 없음.

### B. 보안/운영 (severity, file:line)
- HIGH: rate limiting 전무(auth/catalog/search). HIGH: webhook HMAC 비밀 하드코딩 `MockPgClient.java:25`. HIGH: JobAdminController 예외메시지 노출 `:41,44,47`.
- MEDIUM: CORS 미설정 `SecurityConfig.java`; BusinessException이 내부 DB id 노출 `GlobalExceptionHandler.java:28`; docker-compose 기본 자격증명; X-Forwarded-For 무검증 신뢰; RefundRequestDto 검증 없음.
- MEDIUM: prod config — HikariCP pool 기본10, server.compression/graceful-shutdown 미설정. (정적자원 캐시헤더는 양호: SpaWebConfig)
- 양호: CSRF off(stateless), JWT RS256 검증 견고, RsaKeyLoader fail-fast, 키 gitignore, 결제 금액 재검증/멱등.

### C. 프론트엔드 (file:line)
- 라우트: `/`,`/login`,`/signup`,`/products/:id`,`/cart`,`/checkout`,`/order/complete`,`/orders/:orderNo`,`/dev`. 나머지(/search,/category,/mypage,/wishlist,/ranking,/sale...)는 wildcard→Home(404 없음).
- 세션: refreshToken 미저장/미사용, refresh 인터셉터 없음 → access 만료 시 조용히 로그아웃: `api.ts:116-126`.
- 로그인 후 cart merge 미호출(FE) → 익명장바구니 유실. PDP에 리뷰 섹션 없음(ReviewBlock 고아). 체크아웃 쿠폰/포인트 `null` 하드코딩 `Checkout.tsx:329`.
- 버그: handleBuyNow 레이스 `ProductDetail.tsx:78-85`; 옵션가 총액 중복계산 `ProductDetail.tsx:211`.
- 헤더 검색 onSubmit no-op `Header.tsx:99`; 햄버거 드로어 없음 `:163`; 카테고리 링크 전부 죽음.
- 디자인: 토큰(green=brand, red=price 역할분리 양호)은 구현됐으나 Pretendard 폰트 미로드(index.html 링크 없음); 다크모드/reduced-motion 없음; Cart 썸네일 SVG placeholder; 스티키 구매바 z-index가 탭바 아래.
- 컴포넌트: CouponChip/FilterBar/ReviewBlock은 Dev에서만 사용(프로덕션 고아).

### D. 빌드/테스트/스키마
- 테스트 63개 클래스(약 47 Testcontainers IT + 16 unit/@WebMvcTest). 도메인별 IT 존재. e2e PurchaseFlowE2ETest 12-step.
- `./gradlew test`는 Docker 필요(non-Docker fast path 없음). `compileJava`는 Node 불필요, 통과 유력. `bootJar/bootRun`은 npm 빌드 의존.
- Flyway V1~V16. 누락 테이블: wishlists, 홈 배너/섹션, 타임딜. products에 sales_count 없음.
- frontend: lint/test 스크립트 없음(typecheck만). React18+Router6+TanStack Query5+Vite5.

### Blast-radius 요약
- 정합성 버그 수정: product/payment/order 서비스 국소 + Order에 paymentKey 컬럼(마이그레이션 V17).
- FE 페이지 추가: 신규 라우트/페이지 파일 추가(기존 페이지 영향 최소).
- 보안 하드닝: 신규 필터/설정(application.yml, SecurityConfig 국소), 기존 흐름 비파괴.
- Wishlist/me 보강: 신규 모듈 + 마이그레이션(기존 비파괴).

## Decisions & Escalations

- 2026-06-20 범위 확정(사용자): 전면 개편 + Mock 유지(실연동 seam) + superdesign 전면 리디자인. 백엔드 풀검증(Docker+JDK21).
- 2026-06-20 Human Feedback: **APPROVED Build** (M1~M6; 머천다이징/Admin SPA/검색facet/실PG라이브/실택배 = 런 범위 밖).
- 2026-06-20 검증환경 escalation 해결: 시스템 JDK21 부재 + JAVA_HOME이 `...jdk1.8.0_281\bin`(잘못된 값). → Temurin 21을 `C:\Users\a\.jdks\temurin-21\jdk-21.0.11+10`에 로컬 설치. **모든 gradle 호출은 `JAVA_HOME=/c/Users/a/.jdks/temurin-21/jdk-21.0.11+10 ./gradlew ...`** 로 실행. Docker 데몬 UP.
- 2026-06-20 베이스라인 GREEN: `compileJava` BUILD SUCCESSFUL(2m32s, JDK21). 변경 전 클린 상태 확정.
- 빌드 모델: 슬라이스는 disjoint 파일셋으로 분할 → 병렬 builder는 메인트리에서 안전. builder는 gradle 미실행(코드+테스트+claim만), 컴파일/테스트 게이트는 conductor가 1회 실행, 적대적 Verify는 별도 클린 worktree.
- 공유파일 보호(M1 builder 수정금지): ErrorCode.java, GlobalExceptionHandler.java, SecurityConfig.java, application.yml, build.gradle.kts, DomainProperties.java. 필요 시 claim에 보고→conductor가 중앙 처리.
- 마이그레이션 버전 중앙배정: V17=order.payment_key(A3), V18=products.sales_count(A5), V19=wishlist_items(C1).

## Build Log

### M1 — 백엔드 정합성 (DONE, commit 09d561b on feat/real-mall-upgrade)
- 3 병렬 빌더(catalog-ranking / order-payment / refund-admin) + conductor 적발 후속수정 4건.
- conductor가 적발·수정한 결함: ① list 쿼리 text-block 공백버그(500) ② findByProductId 빈리스트 회귀 ③ INVENTORY_COMMIT_FAILED 고아이벤트(oversell) ④ 테스트 Instant 바인딩.
- conductor smoke: `./gradlew test`(JDK21+Docker) 9 클래스 = 69 tests GREEN.
- 잔여 적대적-검증 대상(최종 Verify에서): 취소 트랜잭션 내 PG호출 후 롤백 시 PG-DB 발산(real PG reconciliation 필요), 전액반품 배송비 환불 정책, refund 누적 라운딩.

### M2 — 보안/운영 하드닝 (IN PROGRESS)
- 2 병렬 빌더(파일소유 disjoint): M2-A(config+edge security: build.gradle/yml/SecurityConfig/RateLimitFilter/MockPg*), M2-B(error hygiene+DLQ ops: GlobalExceptionHandler/JobAdmin/DeliveryAdmin/Outbox requeue).
- conductor 수정: bucket4j 좌표(8.10.1→8.14.0), matchesProfiles API오용. yaml 쓰기 불가→B4를 Java ServerTuningConfig로.
- conductor smoke: 14 클래스(M2+횡단회귀) GREEN(5m40s). **commit 4f2658c**.

### M3 — 신규 백엔드 기능 (IN PROGRESS)
- 2 병렬 빌더: M3-A(Wishlist 모듈+V19+tests), M3-B(/api/me/summary 보강 + /api/categories|brands/{id}/products + SecurityConfig 2 permits).
- conductor smoke: 5 클래스 GREEN(2m32s). **commit 418e041**. (C2 /api/me/summary는 이미 존재 — 검증만)

### M6 — 구조 전면 개편 (DONE, behavior-preserving)
- 단일 드라이버(opus) 리팩터: OrderService 1009→facade+3서비스. 죽은 중복 delivery 클라이언트 2 + order/MemberAddress 스텁 git rm.
- conductor smoke: order/payment/delivery/inventory/e2e 전체 GREEN(3m). **commit 대기**.

### M4/M5 — 프론트엔드 완성 + superdesign 리디자인 (IN PROGRESS)
- FE-A(기반): 세션/리프레시, cart merge, lib 클라이언트, Pretendard, 토큰/스켈레톤/reduced-motion, 라우트+404, 플레이스홀더 6페이지. conductor 수정: @types/node(기존 빌드 breakage 해소). `npm run build` GREEN. **commit 대기**.
- 다음: FE-B 페이지별 병렬(Search/List, MyPage/Orders/Wishlist, Header/Home, PDP/Checkout) — 기능+비주얼 동시.
- FE-B 4 병렬 완료, 통합 `npm run build` GREEN(139 modules). **commit 5c4cf1b**.

### Verify/Committee/Deliver (최종)
- 전체 `./gradlew test`(386+): 초기 3 실패 → LogbackAuditLoggerIT(flaky), ProductSchema(내 regression), PointService(pre-existing 날짜버그)로 판별(main worktree 대조).
- 커미티(architect/security/code) + 완전성 비평(critic) 적대적 리뷰 → BLOCK 사유 전부 수정:
  - FIX-1(money): over-refund 비관락+누적상한, 취소액=승인−환불, recordInventoryCommitFailure REQUIRES_NEW, 빈환불 가드.
  - FIX-2(security): _test/me 명시규칙, RateLimit 바운드 LRU + AC4 통합 IT, rejectedValue null, webhook prod 가드, DLQ truncate, Wishlist 멱등.
  - FIX-3(FE): 단일-flight refresh, 체크아웃 포인트 clamp, PDP 위시리스트 하이드레이션.
  - FIX-4(tests): ProductSchema enable_seqscan 결정화(regression), PointService 시간기준 앵커(pre-existing).
- verification.md: Coverage 맵 + Not covered(8) + Regression tests + verdict GREEN.
- **delivery-gate.sh: GATE PASS** — artifacts present / verification GREEN no RED / completeness contract / 전체 `./gradlew test` 그린.

## Final status: DELIVERED (branch feat/real-mall-upgrade)
- 7 commits: 09d561b(M1) 4f2658c(M2) 418e041(M3) 510d5c4(M6) 78543f3(FE-A) 5c4cf1b(FE-B) c2bff79(committee fixes).
- 백엔드 full `./gradlew test` GREEN(JDK21+Docker). 프론트 `npm run build` GREEN(139 modules).
- 미push/미merge — 사용자 승인 후 push/PR 예정.
- 잔여(Not covered): FE 자동테스트, 브라우저 구동 QA, 실PG reconciliation, prod yaml 주입, V6 트리거 CANCEL 부호버그(후속 V20).
