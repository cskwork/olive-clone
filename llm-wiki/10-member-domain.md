# Member Domain

**Summary:** Owns user accounts, authentication, authorization, addresses,
membership grade, and login history (PRD §6.1, §7.1, §14). Member ID is the
primary key for orders, reviews, coupons, points — get this right.

**Invariants & Constraints:**

- Tables: `members`, `member_addresses`, `member_grades`, `member_login_histories`.
- Email is `UNIQUE NOT NULL`. `password_hash` uses **bcrypt** (cost ≥ 12) or
  Argon2 — never raw, never reversible (PRD §14.3).
- Auth uses **JWT access token + refresh token** (PRD §14.1). Tokens carry
  the role; the role hierarchy is exactly:
  - `USER` — 일반 사용자
  - `CS_MANAGER` — 고객센터
  - `PRODUCT_ADMIN` — 상품 관리자
  - `ORDER_ADMIN` — 주문 관리자
  - `SUPER_ADMIN` — 최고 관리자
- Admin endpoints (`/api/admin/**`) require any role above `USER`. Apply
  least-privilege per resource: PRODUCT_ADMIN can `POST /api/admin/products`
  but not `PATCH /api/admin/orders/*/status`.
- Personally identifiable info (phone, address) lives in `member_addresses`
  table separated from `members` (PRD §6.1, §14.3) so you can mask/encrypt
  without touching the auth path.
- Login history (success + failure) is written to `member_login_histories`
  for the audit log requirement (PRD §16.2).

**Files of interest:**

- PRD §6.1, §7.1, §14.

**Decision log:**

- 2026-05-10 | seed | bcrypt with cost factor 12 minimum.
- 2026-05-10 | seed | Access token TTL 30 min, refresh token TTL 14 days.
- 2026-05-10 | OLV-005 | `MemberRole` enum 이 `com.olive.commerce.member.MemberRole`
  로 골격 단계에 진입. 5 항목: `USER, CS_MANAGER, PRODUCT_ADMIN, ORDER_ADMIN, SUPER_ADMIN`
  (선언 순서 = 권한 강도 오름차순이 아니다 — hierarchy 는 별도 graph). `authority()`
  헬퍼는 `"ROLE_" + name()` 을 반환. JWT `role` claim 은 `name()` 그대로.
- 2026-05-10 | OLV-005 | URL 매처 분기:
  `/api/admin/**` → `hasAnyRole(CS_MANAGER, PRODUCT_ADMIN, ORDER_ADMIN, SUPER_ADMIN)` (4 admin),
  `/api/**` → `hasRole(USER)` (hierarchy 의해 admin 4 도 통과).
  per-resource least-privilege (예: `PRODUCT_ADMIN` 만 product 관리) 는 컨트롤러
  `@PreAuthorize` 에서 강제. 본 티켓은 `/api/admin/products` placeholder 한 건만
  `@PreAuthorize("hasRole('PRODUCT_ADMIN')")` 시연.
- 2026-05-10 | OLV-010 | `V2__member.sql` 적용 — 5 테이블
  (`members` / `member_addresses` / `member_grades` / `member_login_histories` /
  `member_refresh_tokens`) + `set_updated_at()` trigger 함수 + BRONZE/SILVER/GOLD
  3건 등급 시드. invariants:
  - `members.email` UNIQUE → b-tree `members_email_key` 자동 생성 (2000행에서
    `Index Scan` 으로 plan 채택 확인).
  - `members.status` CHECK `IN ('ACTIVE','SUSPENDED','DELETED')` — 잠금 상태는
    Redis (PRD §14) 가 따로 관리, 본 컬럼은 영구 상태만.
  - `member_addresses` partial unique `(member_id) WHERE is_default = TRUE` —
    회원당 default 배송지 1건을 DB 가 강제, 어플리케이션 race 차단.
  - `member_login_histories.member_id` NULLABLE + `ON DELETE SET NULL` —
    UNKNOWN_EMAIL 실패도 감사 로그 보존, 회원 삭제 시 anonymize 보존
    (PRD §16.2 + GDPR).
  - `member_login_histories(member_id, login_at DESC)` 인덱스 — 감사 조회
    "특정 회원의 최근 로그인 N건" 패턴.
  - `member_refresh_tokens.token_hash` CHAR(64) UNIQUE — SHA-256 hex.
    plain refresh token 은 절대 영속화 금지 (OLV-005 contract).
  - 활성 토큰 lookup: partial index `(member_id) WHERE revoked_at IS NULL`.
  - `BIGSERIAL` 채택 (PRD §18 Postgres) — MySQL `AUTO_INCREMENT` 문법 금지.
  - `updated_at` 갱신은 trigger 로 native SQL 경로 (배치/admin) 까지 일관
    유지 — JPA `@PreUpdate` 만 의존 시 native query 가 우회.

**Last updated:** 2026-05-10 by OLV-010.
