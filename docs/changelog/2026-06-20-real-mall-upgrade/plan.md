# Plan — 실제 쇼핑몰 수준 개선 (frozen)

> Mode: LEGACY. Scope (user-confirmed): **코드 구조 전면 개편 + MVP 신뢰성 + 머천다이징 + superdesign 전면 리디자인**.
> Payment: **Mock 유지 + 실연동 seam 정리**(라이브 PG 비목표). Verification: **Docker+JDK21 풀검증**(./gradlew test + frontend build).

## Strategy & Milestones

전체 범위가 매우 크므로 **마일스톤 단위로 구현→검증→커밋**한다. 각 마일스톤은 독립적으로 그린이며 단독 배포 가능.

- **M1 — 백엔드 정합성/신뢰 (Wave A)**: 조용한 정합성 버그 제거. 실제 쇼핑몰의 "믿을 수 있음"의 핵심.
- **M2 — 보안/운영 하드닝 (Wave B)**: rate limit, 비밀 외부화, CORS, 에러노출, prod config.
- **M3 — 신규 백엔드 기능 (Wave C)**: Wishlist, /api/me 보강, category/brand 하위리소스, rankings.
- **M4 — 프론트엔드 기능 완성 (Wave D)**: 세션/리프레시, cart merge, 누락 페이지(검색/카테고리/마이페이지/주문내역/위시리스트/404), 체크아웃 쿠폰·포인트, PDP 리뷰, 헤더, FE 버그.
- **M5 — superdesign 전면 리디자인 (Wave E)**: 디자인시스템 갱신 + 전 페이지 비주얼.
- **M6 — 코드 구조 전면 개편 (Wave F)**: OrderService 분리, 죽은 중복 코드 제거, 공용 헬퍼 추출.

> 권장 구현 순서: M1 → M2 → M3 → M6(리팩터는 신규기능 안착 후) → M4 → M5. (백엔드 먼저 안정화 후 FE/디자인.)
> Out of run scope (별도 합의): 실 PG 라이브 키, 실 택배사 API, Today's Deals/Sale/Event 머천다이징, 추천엔진, Admin SPA, 검색 facet 필터. (원하면 M7+로 추가 — Human Feedback에서 조정.)

---

## Architecture (grounding — Track B deepenings)

Vocabulary: Module = interface+impl, Depth = leverage behind a small interface, Seam = where an interface lives.

1. **OrderService(1009 LOC) god class 분리.** 현재 생성/취소/조회/관리자상태변경이 한 클래스에 혼재(shallow, 낮은 locality). 분리:
   - `OrderCreationService` — 8-step 생성 파이프라인(기존 OrderPricingCalculator 활용).
   - `OrderCancellationService` — **취소↔PG취소↔재고복원↔쿠폰/포인트 보상**을 한 단위로 응집(현재 취소-PG 버그가 사는 곳; 응집이 locality 상승). 보상 실패 silent-swallow 제거.
   - `OrderQueryService` — 목록/상세/관리자 상태조회.
   - `OrderService`는 얇은 facade 유지(컨트롤러 호출부 비파괴) 또는 컨트롤러를 신규 서비스로 직접 라우팅. **deletion test**: 분리는 취소-보상 복잡도를 한곳에 모으므로(이동이 아닌 집중) keep.
2. **죽은 중복 delivery 클라이언트 제거.** `delivery/CarrierClient.java`,`delivery/MockCarrierClient.java`(루트) = 구식 인터페이스/스텁. 활성 빈은 `delivery/client/*`. deletion test: pass-through/dead → 삭제(IDE 혼동·빈 모호성 제거). `order/MemberAddress.java`(@Deprecated 스텁)도 미참조 시 제거.
3. **공용 헬퍼 추출(보수적).** 명백 중복인 `orElseThrow(BusinessException)`/검증 패턴만 `common/util`로. 1줄 wrapper 금지.

## Architecture (grounding — Track A features)

- **Wishlist** 신규 aggregate: `wishlist_items(member_id, product_id, created_at)`, UNIQUE(member_id, product_id). 인터페이스 `WishlistService.add/remove/list(memberId, pageable)`. cart 패턴 미러링(깨끗한 seam).
- **MyPage summary**: 기존 PointService/CouponService/OrderService read를 `MyPageSummary` DTO로 **조합**(로직 중복 금지).
- **Rankings**: `ProductRankingJob`이 order_items(판매수) + product_review_summaries(평점/리뷰수) + (옵션)views로 rank_score 계산해 `product_rankings` 채움. `products.sales_count` 비정규화 컬럼 추가(V17/V18). `/api/rankings`,`/api/best-sellers` 공개. POPULAR 정렬은 rankings 기반.

## Contracts (new/changed)

| Endpoint/Behavior | Change |
|---|---|
| `GET /api/products` 응답 reviewCount/avgRating | product_review_summaries JOIN으로 실제값(현 0 하드코딩 제거) |
| `GET /api/products?sort=POPULAR\|RATING` | rankings/summary 기반 실제 정렬(현 id DESC fallback 제거) |
| `GET /api/categories/{id}/products` | 신규 — 카테고리 딥링크 목록(페이지네이션/정렬) |
| `GET /api/brands/{id}/products` | 신규 — 브랜드관 목록 |
| `GET /api/me` (또는 `/api/me/summary`) | grade+points balance+coupon count+order count 보강 |
| `GET/POST/DELETE /api/me/wishlist` | 신규 — 찜 목록/추가/삭제 |
| `GET /api/rankings`, `GET /api/best-sellers` | 신규 — 랭킹/베스트 |
| `POST /api/orders/{orderNo}/cancel` | PG 취소 호출 연결(Order.paymentKey 사용) |
| Refund partial | 수량 비례 부분환불 계산(RefundService) |
| `olive.pg.webhook-secret` config | webhook HMAC 비밀 외부화(하드코딩 제거) |
| CORS, rate-limit filter | 신규 보안 설정 |

## Design (UI/UX overlay — taste-skill v2)

**Design Read:** 신뢰감 있는 한국형 H&B 커머스 — 깔끔·고밀도 상품 그리드, 브랜드 그린은 CTA/활성에만, 가격/할인 레드 역할 고정, 모바일 퍼스트.

**Dials (frozen):**
- `DESIGN_VARIANCE = medium` — 익숙한 커머스 패턴 유지하되 정제된 차별화(실험적 금지: 구매 신뢰 우선).
- `MOTION_INTENSITY = low-medium` — 빠릿한 마이크로 인터랙션(카드 hover lift, 스켈레톤), 과한 모션 금지, `prefers-reduced-motion` 폴백 필수.
- `VISUAL_DENSITY = medium-high` — OY식 상품 그리드 밀도(2/4/5열), 섹션 간 여백은 넉넉.

**Design system:** 기존 토큰 시스템(green=brand, red=price) **유지·격상**(임의 aesthetic 아닌 실 디자인시스템). Pretendard 폰트 로드 추가, 타입스케일/간격/라운드 정합, 컴포넌트 통일. superdesign으로 Home/PDP/List 비주얼 방향 도출 후 토큰에 맞춰 구현.

---

## Slices (frozen — each independently testable)

> 각 슬라이스: ≤~5 파일/~500 LOC 지향, 자체 acceptance check. 백엔드는 신규/수정 테스트 동반, FE는 typecheck+build로 검증.

### Wave A — 백엔드 정합성 (M1)
- **A1 리뷰 별점 실데이터** — `ProductPublicService`,`ProductDtos`: product_review_summaries JOIN, reviewCount/avgRating 실제값. *check:* ProductPublicApiIT에 리뷰 있는 상품의 avgRating>0 단언 추가 그린.
- **A2 정렬 실동작** — POPULAR(rankings)/RATING(summary) 실제 정렬. *check:* 정렬별 결과 순서 단언 IT 그린.
- **A3 주문취소↔PG취소 정합** — Order.paymentKey 컬럼(V17), `OrderCancellationService`가 PAID/PREPARING 취소 시 paymentService.cancelPayment 호출. *check:* OrderCancelApiIT에 PG취소 호출/환불 단언 그린.
- **A4 결제 중복결제 방지 보강** — inventory.commit 실패가 PG승인 롤백→재시도 중복결제 위험 제거(승인 수용/커밋 분리 또는 멱등 선검사). *check:* PaymentConfirmApiIT 재시도 시나리오 그린.
- **A5 랭킹잡 실계산 + sales_count** — ProductRankingJob 실계산, products.sales_count(V18), `/api/rankings`. *check:* BatchJobExecutionTest 랭킹 채움 단언 + RankingApiIT 그린.
- **A6 부분환불 계산** — RefundService 수량비례. *check:* RefundServiceTest 부분환불 금액 단언 그린.
- **A7 admin 감사/경계** — Coupon/Inventory adminId from SecurityContext; findByProductId(null) 차단. *check:* 관련 IT/단위 그린.

### Wave B — 보안/운영 (M2)
- **B1 rate limit** — bucket4j 필터(/api/auth/* 엄격, /api/search/*, catalog). *check:* 초과요청 429 IT 그린.
- **B2 비밀 외부화 + CORS + mock 스코프** — webhook secret→config, CORS allowlist, MockPgController @Profile/role. *check:* 설정 바인딩 + CORS preflight 단위 그린.
- **B3 에러노출 차단** — JobAdmin/DeliveryAdmin 제너릭 메시지, BusinessException 내부 id 제거, RefundRequestDto @Valid. *check:* GlobalExceptionHandlerTest 확장 그린.
- **B4 prod config** — HikariCP sizing, server.compression, graceful shutdown, Outbox DLQ requeue 엔드포인트+metric. *check:* application.yml 바인딩 + DLQ requeue IT 그린.

### Wave C — 신규 백엔드 기능 (M3)
- **C1 Wishlist** — entity/repo/service/controller + 마이그레이션 + IT. *check:* WishlistApiIT(add/list/remove/unique) 그린.
- **C2 /api/me 보강** — MyPageSummary 조합 DTO. *check:* MemberProfileApiIT 확장(points/coupons/orders) 그린.
- **C3 category/brand 하위리소스** — `/api/categories/{id}/products`,`/api/brands/{id}/products`. *check:* PublicApiIT 그린.

### Wave D — 프론트엔드 기능 완성 (M4)
- **D1 세션/리프레시** — refreshToken 저장, 401 시 자동 refresh 인터셉터, 로그인 후 cart merge 호출. *check:* typecheck+build, 로직 단위(가능시 vitest).
- **D2 누락 페이지** — Search(결과+autocomplete), Category/List, MyPage hub+OrderHistory+OrderDetail/cancel, Wishlist, 404. 라우트 추가. *check:* typecheck+build, 라우트 렌더.
- **D3 체크아웃 보강** — 쿠폰/포인트 적용 UI(백엔드 연동), 주소록 관리. *check:* typecheck+build.
- **D4 PDP 리뷰 + FE 버그** — ReviewBlock 연결+리뷰작성, buyNow 레이스/옵션가 총액 버그 수정. *check:* typecheck+build.
- **D5 헤더/네비** — 검색 동작, 모바일 드로어, 라이브 메가메뉴, 장바구니 뱃지. *check:* typecheck+build.

### Wave E — superdesign 리디자인 (M5)
- **E1 디자인시스템 갱신** — Pretendard 로드, 토큰/타입/간격 정합, reduced-motion, 스켈레톤/빈상태 공통화. superdesign 방향 도출.
- **E2 페이지 비주얼** — Home/PDP/List/Cart/Checkout/MyPage 리스타일, 반응형/접근성. *check:* typecheck+build + taste Pre-Flight(QA).

### Wave F — 구조 전면 개편 (M6)
- **F1 OrderService 분리** — Creation/Cancellation/Query 서비스 + facade. *check:* 전 order IT 그린(무회귀).
- **F2 죽은 중복 제거** — 루트 delivery CarrierClient/MockCarrierClient, MemberAddress 스텁. *check:* compileJava + 전체 테스트 그린.
- **F3 공용 헬퍼** — 명백 중복만 추출. *check:* 전체 테스트 그린.

---

## Risks
- 백엔드 리팩터(F1)는 컴파일/테스트 검증 가능해야 안전 → Docker+JDK21 확인 후 착수.
- Testcontainers 풀스위트 느림(@DirtiesContext E2E) → 반복 중엔 스코프 테스트, 최종만 풀게이트.
- superdesign 리디자인은 기능 회귀 위험 → E는 D 안착 후, 비주얼만 변경(데이터 흐름 비파괴).
- 범위 과대 → 마일스톤별 그린/커밋, 미완 머천다이징은 Not covered 명시.

---

## Human Feedback

### Plain-language brief
지금 이 쇼핑몰은 뼈대(상품·장바구니·주문·결제·검색)는 잘 갖춰져 있지만, 손님이 "믿고 끝까지 살 수 있는" 단계에는 못 미칩니다. 예를 들어 모든 상품이 리뷰가 있어도 별점 0개로 보이고, 주문을 취소해도 실제 결제 취소가 일어나지 않으며, 검색창·마이페이지·주문내역·찜 같은 화면이 아직 없습니다. 이번 작업은 이런 "조용한 버그"를 없애고, 빠진 화면을 채우고, 보안(과도한 요청 차단·비밀값 정리)을 강화하고, 디자인을 올리브영 느낌의 깔끔한 비주얼로 전면 단장합니다. 코드가 너무 커진 부분(주문 처리 1000줄 클래스)도 안전하게 쪼갭니다. 결제는 실제 카드 연동 대신 지금의 모의결제를 유지하되, 나중에 실제 결제사로 바꾸기 쉬운 구조로 정리합니다. 전부 한 번에가 아니라 마일스톤(M1~M6)으로 나눠 만들고 검증하고 커밋합니다.

### Technical brief
LEGACY 개선. 6개 웨이브로 진행: (A) 백엔드 정합성 — ProductPublicService의 reviewCount/avgRating를 product_review_summaries와 JOIN해 실값화, POPULAR/RATING 정렬 실동작, Order에 paymentKey 컬럼(Flyway V17) 추가 후 취소 시 PG 취소 연결, 결제 inventory-commit 순서 보강으로 재시도 중복결제 차단, ProductRankingJob 실계산 + products.sales_count(V18), 부분환불 비례계산. (B) 보안 — bucket4j rate limit 필터, webhook HMAC 비밀을 olive.pg.webhook-secret로 외부화, CORS allowlist, 관리자/예외 메시지의 내부 식별자·스택 누출 차단, HikariCP 풀사이즈·gzip·graceful shutdown·Outbox DLQ requeue. (C) 신규 — Wishlist 모듈(wishlist_items 테이블), /api/me 요약 보강, /api/categories|brands/{id}/products. (D) 프론트엔드 — refreshToken 저장+401 자동 refresh+로그인 후 cart merge, 누락 라우트(Search/Category/MyPage/OrderHistory/Wishlist/404) 추가, 체크아웃 쿠폰·포인트 UI, PDP 리뷰 연결, buyNow 레이스·옵션가 총액 버그 수정, 헤더 검색·드로어·메가메뉴·장바구니 뱃지. (E) superdesign 전면 리디자인 — Pretendard 로드, 디자인 토큰 격상, 전 페이지 리스타일, reduced-motion/접근성. (F) 구조개편 — OrderService를 Creation/Cancellation/Query 서비스로 분리(facade 유지), 죽은 중복 delivery 클라이언트 제거. 구현은 ultracode(Workflow 멀티에이전트)로 슬라이스 병렬, 어댑터티브 Verify(Builder≠Verifier, 클린 worktree에서 재실행) + architect/security/code 커미티 + delivery-gate.sh(./gradlew test + frontend build). 권장 순서 M1→M2→M3→M6→M4→M5.

### Terms
- LEGACY 모드: 기존 코드 개선/기능추가 파이프라인(신규제품 아님).
- Flyway 마이그레이션: 버전드 DB 스키마 변경 스크립트(Vn__name.sql).
- 멱등(idempotency): 같은 요청을 여러 번 보내도 효과는 한 번만 — 중복결제/중복주문 방지.
- Outbox DLQ: 비동기 이벤트가 계속 실패하면 보관하는 사망편지큐(Dead Letter Queue) — 유실 방지·재처리.
- rate limit: 단위시간당 요청 수 제한(무차별 대입·과부하 방지). bucket4j는 그 라이브러리.
- denormalization(비정규화): 조회 속도 위해 집계값(별점/판매수)을 별도 컬럼/테이블에 미리 저장.
- facade: 내부를 여러 클래스로 쪼개되 외부 호출부는 그대로 두는 얇은 진입점.
- taste Pre-Flight: 디자인 품질(접근성·모션폴백) 자동 점검 QA 게이트.
- superdesign: 디자인 비주얼 방향 도출·구현용 스킬.

### Approval request
위 계획대로 Build를 진행해도 될까요? 승인(Approve Build) / 범위 조정(예: 머천다이징·Admin SPA 추가, 또는 특정 웨이브 제외) / 중단 중 선택해 주세요. 또한 백엔드 풀검증을 위해 Docker Desktop 실행 + JDK21 준비가 되면 알려주세요(준비 전엔 프론트엔드부터 진행 가능).
