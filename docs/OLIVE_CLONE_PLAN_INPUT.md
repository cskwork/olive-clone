# Olive Young Clone — Modernization Map (Plan Input)

> Self-hostable Olive Young (올리브영) storefront on the existing `com.olive.commerce` Spring Boot 3.3 / Java 21 modular monolith.
> Source-verified 2026-05-29. This is planning input — feed to `planner`/`to-issues` to generate tickets.

---

## 1. Current State Summary

**Mature backend (~75%).** 262 Java files, 13+ domain modules, clean controller/service/repository layering, `BusinessException` + 61-code `ErrorCode` enum, transactional outbox, Redis cache-aside, Redisson SKU locks, ShedLock, Actuator + Prometheus + Grafana, Testcontainers, k6 load suite. Core commerce flow is fully wired: Product, Category, Brand, Auth (JWT skeleton), Cart (member + anonymous + merge), Order (8-step pipeline), Payment, Delivery, Review, Search (OpenSearch), Promotion (Coupons/Points), Inventory, Batch, Admin CRUD.

**Stub / no frontend.** `findings.frontend == null`. There is a server-side `ui` package only. No SPA, no design system, no storefront pages. **This is the single largest gap** — the entire customer-facing layer must be built.

**Key debt (verified).**
- **Duplicate delivery package**: `com.oliveyoung.oliveyoung.delivery` is a full directory tree (carrier/config/controller/domain/dto/event/repository/service) but contains **0 `.java` files** — dead scaffolding never scanned by `@SpringBootApplication`. The real, active delivery code (21 files) lives in `com.olive.commerce.delivery`.
- **Reserved-word `public` package**: `com/olive/commerce/public/` holds 2 deprecated stubs declaring `package com.olive.commerce.public_deprecated;` — migration to `public_api` is incomplete.
- **God classes**: OrderService 1093 LOC, CartService 643, PaymentService 543, InventoryService 444, ProductPublicService 435.
- **Mocked integrations**: `MockPgClient` (no real Toss PG), `MockCarrierClient` (no CJ/Lotte/Hanjin).
- **Auth inconsistency**: Cart uses `@AuthenticationPrincipal AuthenticatedUser`; Point/MemberCoupon controllers parse `memberId` from username string (TODO), fall back to `1L`.
- **Validation thin**: 104 `@Valid` across 262 files; 161 manual null checks; scattered domain constants.
- **22 open TODOs** block ranking, ratings, partial refunds, admin audit IDs.

Note: a `NotificationService` and `MemberProfileController` already exist (partial), so those are "complete/wire-up", not "build from zero".

---

## 2. Gap Analysis — Olive Young Feature × Backend × Frontend × Priority

| OY Feature | Backend status | Frontend status | Priority |
|---|---|---|---|
| Home / merchandising landing | Sections API missing (`/api/me/home`) | Missing | P1 |
| GNB + category mega-menu | Category tree API done; no `/api/categories/{id}/products` | Missing | P1 |
| Product list / category | `ProductPublicService` done; rating/popular sort = ID placeholder | Missing | P1 |
| PDP (detail/options/images) | Done (`GET /api/products/{id}`) | Missing | P1 |
| Cart (member+anon+merge) | Done (`CartService`) | Missing | P1 |
| Checkout / order | Done (8-step `OrderService`) | Missing | P1 |
| Payment (Toss PG) | **Mocked only** (`MockPgClient`) | Missing | P0/P2 |
| Login / signup | `AuthService` done; JWT extraction inconsistent | Missing | P1 |
| My Page hub | `MemberProfileController` partial; `GET /api/me` = OLV-010 stub | Missing | P3 |
| Wishlist / 찜 | **Missing entirely** (no entity/service/API) | Missing | P3 |
| Reviews | `ReviewService` done; avg-rating not denormalized | Missing | P3 |
| Search + autocomplete | Search/autocomplete/popular done; facet filters TODO | Missing | P3 |
| Address book | `MemberAddressService` done | Missing | P1 (checkout dep) |
| Coupons | Admin issue done; member `/api/me/coupons` done | Missing | P4 |
| Points / 적립금 | `PointService` done | Missing | P4 |
| Ranking / 랭킹 | `ProductRanking` entity exists; job has no calc; **no `/api/rankings`** | Missing | P4 |
| Today's Deals / 오늘드림 | **Missing entirely** (no deal entity/API) | Missing | P4 |
| Sale (세일) landing | No aggregation/landing API | Missing | P4 |
| Brand 브랜드관 page | No `/api/brands/{id}/products` | Missing | P4 |
| Event / 기획전 | Missing | Missing | P4 |
| Personalized home / 추천 | Missing (no recommendation svc) | Missing | P4 |
| Notifications | `NotificationService` partial (not wired to events) | Missing | P3 |
| Sales analytics dashboard | Missing | Missing (admin) | P4 |

P0 = blocker for any real money; P1 = core shop flow; P2 = real payments; P3 = member UX; P4 = merchandising depth.

---

## 3. Prioritized Refactor List (with file refs)

Do these in Phase 0 — they de-risk every later phase. All paths under `src/main/java/`.

1. **DELETE dead `com.oliveyoung.oliveyoung` tree** (CRITICAL). `com/oliveyoung/oliveyoung/delivery/**` — 0 `.java` files, never scanned. Pure noise that confuses navigation. Confirm no Flyway/config references, then `git rm -r`.
2. **DELETE reserved-word `public` package** (HIGH). `com/olive/commerce/public/BrandPublicController.java`, `CategoryPublicController.java` — deprecated stubs; live versions already in `com/olive/commerce/public_api/`. Remove dir; grep for stray `public_deprecated` imports.
3. **Centralize domain constants** (MEDIUM, high leverage). `FREE_SHIPPING_THRESHOLD` (30000), `DEFAULT_SHIPPING_FEE` (3000), `RESERVATION_TTL` (15m), `ANON_CART_TTL` (30d) inlined in `order/OrderService.java:55-58`, `cart/CartService.java:40-41`. Create `common/config/DomainProperties.java` (`@ConfigurationProperties("olive.domain")`), bind via `application.yml`.
4. **Unify JWT principal extraction** (HIGH). `promotion/PointController.java:42`, `promotion/MemberCouponController.java` parse username / fall back to `1L`. Adopt `@AuthenticationPrincipal AuthenticatedUser.memberId()` (the `cart/CartController` pattern). Removes a security/correctness hazard before member UX phases.
5. **Validation hardening** (HIGH). Add `@Valid` to all `@RequestBody` DTOs; annotate DTOs (`@NotNull/@NotBlank/@Positive`); extend `GlobalExceptionHandler` to map `MethodArgumentNotValidException` → `ApiResponse` field-error list. Worst offenders: `promotion/PointController.java`, `payment/PaymentController.java`.
6. **Split god classes** (HIGH, scoped — only touch when the phase needs them). `order/OrderService.java` → `OrderCreation` + `OrderPricingCalculator` + `OrderEventPublisher`; `cart/CartService.java` → `CartMergeService` + `CartValidationService`; `payment/PaymentService.java` → `PaymentTransactionRecorder` + `PaymentStateTransitionHandler` + `PaymentGatewayAdapter` (eases real-Toss swap).
7. **Extract shared helpers** (MEDIUM). 25+ duplicated `validate*`/`build*` methods → `common/util/ValidationUtils` + `ResponseBuilder`. Standardize 80 `orElseThrow(BusinessException)` patterns.

---

## 4. Infra / Self-Host + High-Traffic Gaps

Stack is self-host-ready today (one-command `docker compose` + `./gradlew bootRun`; Postgres, Redis, OpenSearch, LocalStack S3, Prometheus, Grafana with health checks; multi-stage Dockerfile with container-aware JVM flags). Production hardening gaps (Phase 5):

- **DB pool unsized** (HIGH): HikariCP defaults `max-pool=10`, fails at 50+ VU. Set `maximum-pool-size=50, minimum-idle=10, connection-timeout=10s, idle-timeout=300s, max-lifetime=1800s` + `SELECT 1` validation.
- **No rate limiting** (HIGH): public `/api/products`, `/api/search`, `/api/auth/*` are unbounded. Add bucket4j per-IP (catalog 100/min, signup 10/min).
- **No HTTP compression** (MEDIUM): `server.compression.enabled=true`, gzip, `min-response-size=512B`.
- **No static cache headers** (MEDIUM): demo product images re-downloaded each load. `WebMvcConfigurer` `Cache-Control: max-age=31536000` + ETag for hashed filenames.
- **No graceful shutdown** (MEDIUM): `server.shutdown=graceful` + `spring.lifecycle.timeout-per-shutdown-phase=30s`; drain outbox workers.
- **Resilience gaps** (MEDIUM): Redisson pool=10 / no backoff; OpenSearch client no retry. Add exponential backoff + jitter, circuit breaker, DB-lock fallback.
- **Outbox no DLQ** (MEDIUM): failing events retry forever → `dlq_outbox` after 5 attempts + `outbox_dlq_count` metric.
- **No OpenAPI docs** (MEDIUM): add `springdoc-openapi-starter-webmvc-ui` at `/api/docs`.
- **Tomcat threads / GC / perf baseline / K8s manifests** (LOW–MEDIUM): `threads.max=200`; document p50/p95/p99 SLOs (product-list p95<300ms, order-create p95<1s); add liveness/readiness probes + resource requests/limits; consider ZGC if GC>5%.

---

## 5. Olive Young Design System (ready to implement)

### Color tokens
```css
/* Brand — green = brand/CTA/active ONLY */
--brand-green: #9BCE26;        /* logo, primary CTA, active nav, selected chips */
--brand-green-dark: #7DA81E;   /* CTA hover/pressed */
--brand-green-tint: #F2F8E2;   /* selected-row bg, success-soft */
/* Ink / grey */
--ink-900: #181818;  --ink-700: #333333;
--grey-500: #767676; --grey-300: #BFBFBF; --grey-200: #E5E5E5; --grey-100: #F4F4F4;
--white: #FFFFFF;
/* Price — red = price/discount ONLY (never mix with green role) */
--sale-red: #FF4452; --sale-red-dark: #E5202E;
/* Functional cues */
--delivery-mint: #00C2B3;  /* 오늘드림 same-day */
--coupon-blue: #2C7BE5;    /* coupon chip */
--rating-star: #FFB400;
--rank-up: #FF4452; --rank-down: #2C7BE5; --rank-steady: #767676;
--overlay-dim: rgba(0,0,0,0.55);
/* --badge-best: #181818 bg + #FFFFFF text (BEST/1+1/증정) */
```
**Hard rule:** green = brand/CTA/active state; red = price/discount only. Never swap roles.

### Typography
- Family: `'Pretendard', 'Noto Sans KR', -apple-system, 'Apple SD Gothic Neo', 'Malgun Gothic', sans-serif`
- Weights: 400 body / 500 labels+prices / 600 card+section titles / 700 H1+price emphasis+discount / 800 hero
- Scale: Hero H1 28/1.3/800 (mobile 24) · H2 20/1.35/700 · H3 16/1.4/600 · Body 14/1.55/400 · Caption 12/1.45/400 · Price 18/1.3/700 (sale 20/700 red) · Discount% 16/700 red
- Letter-spacing: `-0.02em` on Korean headings; `0` on numerals/prices

### Spacing / sizing
- Base 4px; scale 0,2,4,8,12,16,20,24,32,40,48,64
- Gutters: 8 intra-card / 12 grid gap / 16 section pad (mobile) / 24 (desktop)
- Rhythm: 32 between rails, 48 between page blocks
- Touch target ≥44×44; sticky bar 56; GNB 48 mobile / 64 desktop
- Container max 1200 centered; grid 2-col mobile / 4 tablet / 5 desktop

### Radius
`--radius-xs:2px` (badges/pills) · `sm:4px` (inputs/buttons) · `md:8px` (cards/images; mobile sheet top 16) · `lg:12px` (modals/banners) · `pill:999px` (filter chips/tabs) · `circle:50%`

### Components (8 reusable)
1. **Product Card** — image (radius-md) + top-left badges (BEST/세일/1+1) + top-right wishlist heart; brand 12px grey; 2-line clamp title 16/600; price block (discount% red + sale 18/700 + strikethrough grey); stars + review count; optional 오늘드림 badge; desktop hover lift. Reused: home rails, list, ranking, search, brand, wishlist.
2. **Rating Stars** — 5-star `#FFB400`, fractional fill; sm 12 / md 16; numeric score + review-count link; `aria-label "평점 4.5 / 리뷰 1,234"`.
3. **Price / Discount Badge** — discount-rate pill/inline % red 700, sale 700, strikethrough grey-500; 1+1/증정 = black `--badge-best`; comma thousands + `원`.
4. **Coupon Chip** — coupon-blue outline + download icon + value ("15% 쿠폰"); states available / downloaded(disabled✓) / expired(grey).
5. **Review Block** — masked id + tier badge, stars, date, skin-type tags, clamp body, photo thumbnails→lightbox, 도움돼요 toggle; PDP tab adds rating-breakdown bar chart.
6. **Image Carousel** — main + thumb strip (desktop) / dots (mobile), hover arrows, PDP pinch-zoom, hero auto-rotate; lazy-load + fixed aspect (no CLS).
7. **Sticky Filter Bar** — 56px, pins under header; 필터 button (→ bottom-sheet) + active-count badge + removable applied-filter pills + sort dropdown; ranking variant adds period/scope tabs.
8. **Quantity / Option Selector** — variant dropdown(s) → selected-option chip rows each with qty stepper + line subtotal + remove; respects stock/min/max; mobile in bottom-sheet from sticky 구매 bar.

**16 pages to build:** Home, GNB Mega-Menu, Ranking, Today's Deals/오늘드림, Sale, Product List/Category, PDP, Cart, Checkout, Login/Signup, My Page, Wishlist/찜, Search Results+Autocomplete, Brand관, Event/기획전, (+ shared GNB/footer chrome).

---

## 6. Phased Roadmap

Each phase: concrete deliverables + the existing backend it leverages. Recommended SPA: mobile-first (360px base), token-driven, talks to existing `public_api` + `/api/me/*`.

### Phase 0 — Refactor / Cleanup (de-risk foundation)
- Delete dead `com.oliveyoung.oliveyoung` tree (§3.1) and reserved `public` package (§3.2).
- Add `DomainProperties` config (§3.3); unify JWT principal extraction (§3.4); validation hardening + `GlobalExceptionHandler` (§3.5).
- Extract `ValidationUtils` + `ResponseBuilder` (§3.7). Split god classes only as later phases require.
- Add `springdoc-openapi` at `/api/docs` to give the SPA a contract.
- **Leverages:** existing `BusinessException`/`ErrorCode`, `common/api/ApiResponse`, `cart/CartController` auth pattern. **Exit:** build + tests green, zero dead packages, OpenAPI published.

### Phase 1 — Storefront Foundation + Design System
- Scaffold SPA (Vite + React or equivalent), routing, token CSS vars (§5), 8 base components, GNB (black) + category mega-menu + footer chrome, login/signup screens.
- Add `GET /api/categories/{id}/products` (category deep-link) — closes a P1 backend gap.
- **Leverages:** `AuthService` (signup/login/refresh/logout), `CategoryPublicService` tree, `public_api` controllers, `MemberAddressService` (later checkout).
- **Deliverables:** running app shell, design-system storybook, working auth screens. **Exit:** login round-trips a real JWT; tokens drive all components.

### Phase 2 — Core Shopping Flow (home → category → PDP → cart → checkout)
- Pages: Home (basic rails from existing data), Product List/Category, PDP, Cart, Checkout.
- Wire Product Card, Image Carousel, Option/Qty Selector, Sticky Filter Bar, Price Badge.
- Payment runs against `MockPgClient` here (real Toss deferred to P2-payment task in Phase 5/hardening or sooner if launch needs money).
- **Leverages:** `ProductPublicService`, `GET /api/products/{id}`, full `CartService` (member+anon+merge), 8-step `OrderService`, `MemberAddressService`, `PaymentService` (mock).
- **Exit:** anonymous browse → add to cart → login/merge → checkout → mock-paid order visible. This is the MVP storefront.

### Phase 3 — Member / My Page / Wishlist / Reviews / Search UX
- **NEW backend:** Wishlist module — `WishlistItem` entity, `WishlistService`, `GET/POST/DELETE /api/me/wishlist`.
- Complete `GET /api/me` (OLV-010 stub) for membership tier/points/coupon summary.
- Denormalize `avg_rating`/`review_count` on Product via review event subscriber; fix `ProductPublicService` RATING sort.
- Add search facet filters (price/rating/in-stock) to `SearchService` + filter bottom-sheet UI.
- Wire `NotificationService` to order-status events.
- Pages: My Page, Wishlist/찜, Review Block UI, Search Results + Autocomplete.
- **Leverages:** `ReviewService`, `SearchService`/autocomplete/popular, `PointService`, `/api/me/coupons`, `MemberProfileController`, existing `NotificationService`.
- **Exit:** member can save/track wishlist, read/write reviews, see accurate ratings, filtered search.

### Phase 4 — Promotions / Ranking / Today's Deals / Merchandising
- **NEW backend:** implement `ProductRankingJob` calc (sales/reviews/views) + `sales_count` on Product via `SalesAggregationJob`; expose `GET /api/rankings` + `GET /api/best-sellers`; fix POPULAR sort.
- **NEW backend:** Today's Deals/오늘드림 — `TimeLimitedDeal` entity (start/end, flash price), `/api/deals`, batch publish/unpublish at boundaries.
- Add `GET /api/brands/{id}/products` (Brand관), Sale aggregation endpoint, Event/기획전 module, rule-based `GET /api/me/home` (recently-viewed + popular-in-categories) recommendations.
- Pages: Ranking, Today's Deals, Sale, Brand관, Event, personalized Home rails; Coupon Chip + rank-delta UI.
- **Leverages:** `ProductRanking` entity, batch infra (ShedLock jobs), `CouponService`/`PointService`, OpenSearch.
- **Exit:** full OY merchandising surface (ranking + deals + sale + brand + events + personalized home).

### Phase 5 — Performance / Load / Hardening
- All §4 infra items: HikariCP sizing, rate limiting (bucket4j), gzip, static cache headers + CDN-style hashed assets, graceful shutdown, Redisson/OpenSearch retry+circuit-breaker, outbox DLQ, Tomcat threads.
- **Real Toss PG integration** (OLV-080): replace `MockPgClient` with `TossPgClient` bean in `payment/config/PgClientConfig`, HMAC webhook verification, signature validation; complete partial-refund proportional calc in `RefundService`.
- Real carrier clients (CJ/Lotte/Hanjin) replacing `MockCarrierClient`; admin sales-analytics dashboard + review-moderation; admin audit `adminId` from `SecurityContext`.
- k6 baseline + SLO regression gates; K8s manifests (liveness/readiness probes, requests/limits); JFR profiling under load.
- **Leverages:** existing Actuator/Prometheus/Grafana, k6 suite, outbox/ShedLock, `PaymentService`/`RefundService`, `DeliveryService`.
- **Exit:** real payments + carriers live, SLOs met under load, rate-limited, gracefully shutting down, K8s-deployable.

---

### Dependency notes
- Phase 0 → 1 → 2 are strictly ordered (refactor before build before flow).
- Phase 3 and 4 are largely parallelizable after Phase 2 (member UX vs merchandising) — split across two squads.
- Real Toss PG (Phase 5) can be pulled earlier if launch must accept money before merchandising depth ships; everything else in Phase 5 is post-MVP hardening.
