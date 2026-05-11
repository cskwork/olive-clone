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
- `src/main/java/com/olive/commerce/auth/{AuthController,AuthService,AuthDtos,LoginAttemptGuard,RefreshTokens}.java` (OLV-011)
- `src/main/java/com/olive/commerce/member/{Member,MemberRefreshToken,MemberLoginHistory,MemberGrade}.java` (OLV-011)
- `src/main/java/com/olive/commerce/member/{MemberRepository,MemberRefreshTokenRepository,MemberLoginHistoryRepository,MemberGradeRepository,MemberProfileController}.java` (OLV-011)
- `src/test/java/com/olive/commerce/auth/AuthApiIT.java` — 8 케이스 end-to-end (OLV-011)

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

- 2026-05-10 | OLV-011 | `/api/auth/{signup,login,refresh,logout}` + `/api/me`
  완성. 핵심 invariants:
  - **login() 은 의도적으로 `@Transactional` 미부착** — 실패 시 BusinessException
    이 동일 트랜잭션의 `member_login_histories` INSERT 까지 rollback 시켜
    감사 트레일이 사라진다 (PRD §16.2 위반). Spring Data 의 repo 단위
    `@Transactional` 으로 각 INSERT 가 독립 커밋된다. signup() / refresh() /
    logout() 은 atomic 보장이 필요하므로 `@Transactional` 유지.
  - **refresh 회전은 `@Lock(PESSIMISTIC_WRITE)` + token_hash UNIQUE 로
    DB 차원 직렬화** — `MemberRefreshTokenRepository#lockByTokenHash`
    가 `SELECT ... FOR UPDATE`. 같은 refresh 가 동시에 두 호출에 들어와도
    한 트랜잭션이 revoked_at 을 commit 한 뒤 다른 트랜잭션이 lockByTokenHash
    를 깨워 `revoked_at IS NOT NULL` 을 보고 `INVALID_REFRESH_TOKEN` 으로
    거절. application-side 락 불필요.
  - **잠금 카운터는 Redis** (`auth:fail:{email}` TTL 10분, `auth:lock:{email}`
    TTL 15분). 5회 임계 도달 시 `setIfAbsent` 로 락 SET 후 fail 키 DEL —
    이후 락 활성 동안에는 비밀번호 검증을 건너뛰어 카운트도 증가 X
    (DDoS 방지). `members.status` 컬럼은 일시 잠금에 사용하지 않는다.
  - **bcrypt cost 12 dummy hash 캐싱** — `passwordEncoder.encode(...)` 결과를
    AuthService 생성자에서 한 번 계산해 인스턴스 필드로 보관. 알려지지 않은
    이메일에도 `matches(req.password, dummyHash)` 를 호출해 timing 차이를
    완화. dummy 가 진짜 bcrypt 형식이 아니면 매 호출마다 Spring Security 가
    `Encoded password does not look like BCrypt` warn 을 남겨 노이즈.
  - **DataIntegrityViolationException race fallback** — `existsByEmail` 검사
    와 `saveAndFlush` 사이의 race 에서 두 번째 호출이 `members.email` UNIQUE
    위배로 떨어진다. 잡아서 `EMAIL_ALREADY_USED` 로 변환.
  - **refresh 토큰 평문은 응답 외 어디에도 노출되지 않음** — DB 에는 SHA-256
    hex(64자)만 저장 (`MemberRefreshToken.issue` + `RefreshTokens.sha256Hex`).
  - **`/api/me` 매처 자동 통과** — SecurityConfig 의 `/api/**`.hasRole("USER")
    에 자연스럽게 포함, 별도 매처 추가 불필요.
- 2026-05-10 | OLV-011 | placeholder controller 의 component-scan 측면 부수효과:
  `AuthService`/`MemberProfileController` 가 component scan 으로 잡히면서
  기존 좁은-context IT (`SecurityFilterChainIT` 등 — DB autoconfig 제외) 의
  컨텍스트 부팅이 JPA repo / Redis 의존으로 깨졌다. 해결: `@MockBean` 로
  domain-bean 의존성을 끊고 (`SecurityFilterChainIT` 5건, `LogbackAuditLoggerIT` 5건),
  `BootstrapTest` 에는 sibling Redis 컨테이너 추가. 향후 도메인 티켓이 추가
  컴포넌트를 등록할 때마다 같은 패턴 반복 — base-class 추출이 5번째 도메인쯤
  할 만한 가치.
- 2026-05-10 | OLV-011 | `JwtTokenProvider#parseRefresh` 추가. 기존 `parseAccess` 와
  공유 헬퍼로 리팩터: typ 비교를 매개변수화. refresh 토큰의 role claim 은 없으므로
  USER 로 하드코드 — refresh 는 단지 "이 회원의 새 access 발급권" 이지 권한
  자체를 운반하지 않는다 (access 발급 시 DB / 사용자 상태로부터 다시 결정).

**Last updated:** 2026-05-10 by OLV-011.
