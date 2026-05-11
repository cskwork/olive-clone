# Security

**Summary:** Authentication, authorization, PII handling, payment data
policy, request audit (PRD §14, §16.2).

**Invariants & Constraints:**

- **Auth** (PRD §14.1):
  - JWT access token (HS256 or RS256), TTL 30 min.
  - JWT refresh token, TTL 14 days, stored hashed in `member_refresh_tokens`.
  - Admin SUPER_ADMIN may require additional 2FA — gate behind a feature flag.
- **Authz** (PRD §14.2): role-based via Spring Security
  `@PreAuthorize("hasRole('PRODUCT_ADMIN')")` style, plus per-resource
  ownership checks on user endpoints (`order.member_id == principal.id`).
- **Password** (PRD §14.3): bcrypt cost ≥ 12, never log raw, never echo
  back in API responses (mask to `null`).
- **PII** (PRD §14.3, §16.2): mask phone (`010-****-1234`) and email
  (`u***@example.com`) in admin list views. Full PII is gated behind a
  separate "view PII" admin permission with audit logging.
- **Payment data** (PRD §14.4): never store PAN, CVC, or raw card details.
  Only `payment_key`, `pg_provider`, `transaction_id`.
- **Request audit** (PRD §16.2): a servlet filter writes `(request_id,
  member_id, role, method, path, status, latency_ms)` for every
  authenticated mutation request. Login failures, admin actions, payment
  events, inventory changes, and coupon use are first-class audit
  categories.
- **Idempotency** (PRD §20.4): all mutating PG callbacks and admin
  mutating endpoints accept `Idempotency-Key` header.

**Files of interest:**

- PRD §14, §16.2, §20.4.
- `src/main/java/com/olive/commerce/common/security/{JwtTokenProvider,NimbusJwtTokenProvider,JwtClaims,JwtConfig,JwtProperties,RsaKeyLoader,AuthenticatedUser,JwtAuthenticationConverter,JwtAuthenticationEntryPoint,JwtAccessDeniedHandler,RoleHierarchyConfig}.java` (OLV-005)
- `src/main/java/com/olive/commerce/common/config/SecurityConfig.java` (OLV-005)
- `src/main/java/com/olive/commerce/member/MemberRole.java` (OLV-005)
- `src/main/resources/keys/{app.key,app.pub,README.md,.gitkeep}` (OLV-005, key 파일은 gitignored)
- `application.yml` `olive.security.jwt.*` (OLV-005)

**Decision log:**

- 2026-05-10 | seed | JWT signing = RS256 with rotated keypair stored in
  AWS Secrets Manager (or local file in dev).
- 2026-05-10 | OLV-001 | `spring-boot-starter-security` + 회원 도메인 부재
  조합은 `InMemoryUserDetailsManager`가 매 기동마다 임의 비밀번호를 로그에
  찍는다 — “시크릿 로깅 금지” 원칙에 위배. SecurityFilterChain만으로는
  막지 못하므로 `UserDetailsServiceAutoConfiguration` 자체를
  `application.yml` `spring.autoconfigure.exclude`로 끊는다. OLV-010(회원
  도메인)에서 실제 `UserDetailsService` 빈을 주입하며 이 라인을 제거한다.
- 2026-05-10 | OLV-004 | request audit 의 첫 골격이 들어왔다 — `RequestIdFilter`
  가 모든 요청에 traceId 를 발급하고, `LogbackAuditLogger` 가 `LOGIN_SUCCESS`
  같은 카테고리 이벤트를 일자 롤링 JSON 으로 남긴다. 카테고리 enum 은
  OLV-100(payment)/OLV-130(observability) 에서 점진적으로 도입. 응답·에러 봉투
  와 traceId 처리는 `01-common-conventions.md` 참조.
- 2026-05-10 | OLV-005 | JWT 검증은 `spring-boot-starter-oauth2-resource-server` +
  `nimbus-jose-jwt` 로 위임 (커스텀 필터 X). 발급은 `NimbusJwtTokenProvider` 가
  RS256 으로 직접 sign — 검증은 라이브러리, 발급은 우리 코드라는 분리. claim 은
  `sub=memberId, role=MemberRole.name(), typ=access|refresh, iss=olive-commerce, iat, exp`
  (refresh 는 추가 `jti`). `JwtAuthenticationConverter` 가 `Jwt → AuthenticatedUser(memberId, role)` +
  단일 `ROLE_<role>` authority 로 변환 — refresh 토큰을 access 경로로 들이밀면
  `InvalidBearerTokenException`.
- 2026-05-10 | OLV-005 | RoleHierarchy 텍스트는 `RoleHierarchyImpl.fromHierarchy(...)`
  (Spring 6.3+ 정적 팩토리). Hierarchy 빈만 있으면 Spring 6 의 `AuthorizationManager`
  가 URL 매처 (`hasRole`/`hasAnyRole`) 와 method 보안 (`@PreAuthorize`) 양쪽에 자동
  적용한다 — 6.x 의 큰 단순화. 따라서 `MethodSecurityExpressionHandler` 는 hierarchy
  를 한 번만 주입하면 method 보안 쪽이 동작.
- 2026-05-10 | OLV-005 | 401/403 envelope 은 `AuthenticationEntryPoint`/`AccessDeniedHandler`
  빈에서 ObjectMapper 로 `ApiResponse.failure(...)` 를 직접 직렬화한다 —
  `@RestControllerAdvice` 는 SecurityFilterChain 보다 안쪽이라 도달하지 않는다.
  `RequestIdFilter` (`HIGHEST_PRECEDENCE`) 가 SecurityFilterChain 보다 먼저
  traceId 를 MDC 에 채우므로 envelope 의 `traceId` 가 보장된다.
- 2026-05-10 | OLV-005 | RS256 (RSA-2048) 서명의 base64url 마지막 char 는 6 비트 중
  4 비트가 padding — 서명 위조 테스트는 *마지막* char 만 바꾸면 padding 비트만
  토글되어 서명 검증을 통과할 수 있다. 위조 테스트는 *서명 세그먼트 중간 char*
  를 바꿔야 안정적.
- 2026-05-10 | OLV-005 | refresh 토큰은 `issueRefresh(memberId)` 평문 JWT 만 반환 —
  `jti` 평문을 호출자(OLV-011 회원 로그인 흐름) 가 hash(`SHA-256` 등) 해서
  `member_refresh_tokens` 에 저장해야 회전·블랙리스트 검증이 가능. Provider 자체는
  persistence 책임 없음.
- 2026-05-10 | OLV-011 | `BCryptPasswordEncoder(12)` 빈은 `SecurityConfig` 의
  `passwordEncoder()` 한 곳에서 노출. cost 12 를 코드로 못 박는다 — yml 설정
  값 의존 시 하향 조정 사고 가능. `AuthService` 생성자에서 dummy hash 를 한 번
  encode 해 두면 user-enumeration timing 차이를 완화하면서도 매 요청마다
  Spring Security 의 `Encoded password does not look like BCrypt` warn 노이즈를
  피할 수 있다 (반드시 진짜 bcrypt 결과여야 함).
- 2026-05-10 | OLV-005 | dev 키는 `src/main/resources/keys/{app.key,app.pub}` —
  `.gitignore` 처리. README 에 `openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048`
  명령. 운영은 `application.yml` 의 `olive.security.jwt.private-key-location` 을
  `file:/run/secrets/...` 로 오버라이드해 K8s Secret 마운트.

**Last updated:** 2026-05-10 by OLV-011.
