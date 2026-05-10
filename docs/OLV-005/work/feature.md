# OLV-005 — Spring Security 골격: JWT 발급/검증 + 5단계 역할 hierarchy

## 사용자(개발자/운영자) 관점에서 무엇이 생겼나

1. **모든 `/api/**` 는 JWT bearer 토큰 인증을 요구한다.** 예외:
   - `/actuator/health`, `/actuator/health/**`, `/actuator/info` — permitAll.
   - `/api/auth/**` — permitAll (회원 가입/로그인/토큰 갱신은 토큰 없이 호출되어야 함).
   - `GET /api/products/**`, `GET /api/search/**` — permitAll (비로그인 카탈로그 조회).
   - `/api/admin/**` — `CS_MANAGER`/`PRODUCT_ADMIN`/`ORDER_ADMIN`/`SUPER_ADMIN` 중 하나.
   - 그 외 `/api/**` — `USER` 이상 (hierarchy 의해 admin 도 통과).

2. **JWT 발급 API (`JwtTokenProvider` 빈)** 은 OLV-011 이후 도메인 티켓이 import 받아 쓴다:
   ```java
   String access  = jwtTokenProvider.issueAccess(memberId, MemberRole.USER);   // TTL 30 min
   String refresh = jwtTokenProvider.issueRefresh(memberId);                   // TTL 14 days
   ```
   `issueRefresh` 가 반환한 평문 JWT 의 `jti` 를 호출자가 hash 해서 `member_refresh_tokens`
   (OLV-011 마이그레이션) 에 저장하면 회전·재발급 검증이 가능해진다.

3. **5 단계 역할 hierarchy 가 동작한다.**
   ```
   SUPER_ADMIN > {PRODUCT_ADMIN, ORDER_ADMIN, CS_MANAGER}
   {PRODUCT_ADMIN, ORDER_ADMIN, CS_MANAGER} > USER
   ```
   `@PreAuthorize("hasRole('PRODUCT_ADMIN')")` 는 `PRODUCT_ADMIN` 토큰과 `SUPER_ADMIN`
   토큰 모두 통과시킨다. URL 매처도 같은 hierarchy 를 자동 적용한다.

4. **컨트롤러는 `@AuthenticationPrincipal AuthenticatedUser` 로 호출자 정보를 받는다.**
   ```java
   @GetMapping
   public ApiResponse<...> getCart(@AuthenticationPrincipal AuthenticatedUser principal) {
       long me = principal.memberId();
       MemberRole role = principal.role();
       ...
   }
   ```

5. **401/403 응답은 OLV-004 의 `ApiResponse.failure(...)` 봉투로 통일된다.**
   - 401 (`AUTHENTICATION_REQUIRED`): 토큰 부재 / 만료 / 위조.
   - 403 (`ACCESS_DENIED`): 토큰 valid 이나 권한 부족.
   - `traceId` 가 자동으로 채워진다 (`RequestIdFilter` 가 SecurityFilterChain 보다 먼저 동작).

   예시 401 응답:
   ```json
   {
     "success": false,
     "error": {
       "code": "AUTHENTICATION_REQUIRED",
       "message": "인증이 필요합니다.",
       "path": "/api/cart",
       "traceId": "11111111-1111-1111-1111-111111111111"
     }
   }
   ```

## RS256 키 운영

- 개발: `src/main/resources/keys/app.key` + `app.pub` (gitignored, OLV-005 가 자동 생성).
- 키 생성: `src/main/resources/keys/README.md` 참조 — `openssl genpkey ... && openssl rsa ...`.
- 운영: Secrets Manager 또는 K8s Secret 마운트. `application.yml` 의
  `olive.security.jwt.private-key-location` / `public-key-location` 을 절대경로로
  오버라이드 (예: `file:/run/secrets/olive-jwt.key`).
- 로드 실패 시 startup fail-fast (`RsaKeyLoader` 에서 `IllegalStateException`).

## 정확히 어떤 파일이 추가/수정됐나

### 신규 (production)
- `src/main/java/com/olive/commerce/member/MemberRole.java` — 5 단계 역할 enum.
- `src/main/java/com/olive/commerce/common/security/`
  - `JwtTokenProvider.java` — 인터페이스 (`issueAccess`/`issueRefresh`/`parseAccess`).
  - `NimbusJwtTokenProvider.java` — RS256 + nimbus-jose-jwt 구현.
  - `JwtClaims.java` — record (`memberId`, `role`, `expiresAt`).
  - `JwtValidationException.java` — 검증 실패용.
  - `JwtProperties.java` — `olive.security.jwt.*` 매핑 (record).
  - `JwtConfig.java` — `JwtTokenProvider`/`JwtDecoder`/RSA 키/Clock 빈.
  - `RsaKeyLoader.java` — PEM(PKCS#8 / X.509) 로더.
  - `AuthenticatedUser.java` — record(`memberId`, `role`).
  - `JwtAuthenticationConverter.java` — `Jwt → UsernamePasswordAuthenticationToken<AuthenticatedUser>`.
  - `JwtAuthenticationEntryPoint.java` — 401 envelope.
  - `JwtAccessDeniedHandler.java` — 403 envelope.
  - `RoleHierarchyConfig.java` — `RoleHierarchy` 빈 + `@EnableMethodSecurity` + `MethodSecurityExpressionHandler`.
- `src/main/java/com/olive/commerce/cart/CartPlaceholderController.java` — `/api/cart` GET (OLV-040 이 대체).
- `src/main/java/com/olive/commerce/admin/AdminProductPlaceholderController.java` — `/api/admin/products` POST `@PreAuthorize PRODUCT_ADMIN` (OLV-022 이 대체).
- `src/main/java/com/olive/commerce/auth/AuthPlaceholderController.java` — `/api/auth/login` POST (OLV-011 이 대체).

### 수정
- `src/main/java/com/olive/commerce/common/config/SecurityConfig.java` — 분기/Resource Server/EntryPoint/AccessDeniedHandler 와이어.
- `src/main/java/com/olive/commerce/common/error/ErrorCode.java` — `AUTHENTICATION_REQUIRED(401)`, `ACCESS_DENIED(403)` 두 항목 append.
- `build.gradle.kts` — `spring-boot-starter-oauth2-resource-server`, `nimbus-jose-jwt:9.40` 의존성, Docker Desktop on macOS 용 `DOCKER_HOST` 자동 탐지.
- `src/main/resources/application.yml` — `olive.security.jwt.*` 블록.
- `.gitignore` — `src/main/resources/keys/*` (단 `.gitkeep`/`README.md` 제외).
- `src/main/resources/keys/README.md` + `.gitkeep` — 키 생성 가이드.

### 신규 (tests)
- `src/test/java/com/olive/commerce/common/security/JwtTokenProviderTest.java` — 6 단위 테스트 (round-trip, 만료, typ/role/jti claim, refresh→parseAccess 거부, 위조 감지).
- `src/test/java/com/olive/commerce/common/security/SecurityFilterChainIT.java` — 9 통합 테스트 (AC 4 건 + hierarchy + tampered token + auth public).
- `src/test/resources/keys/app.key`, `src/test/resources/keys/app.pub` — 테스트 전용 RSA 2048 keypair (운영과 분리).

## 후속 티켓이 사용하는 방법

OLV-011 (회원 로그인) 컨트롤러:
```java
@PostMapping("/login")
public ApiResponse<TokenPair> login(@Valid @RequestBody LoginRequest req) {
    Member member = memberAuthService.authenticate(req.email(), req.password());
    String access = jwtTokenProvider.issueAccess(member.id(), member.role());
    String refresh = jwtTokenProvider.issueRefresh(member.id());
    refreshTokenStore.save(member.id(), DigestUtils.sha256Hex(refresh));
    return ApiResponse.success(new TokenPair(access, refresh));
}
```

OLV-022 (admin 상품 등록) 컨트롤러:
```java
@PostMapping("/api/admin/products")
@PreAuthorize("hasRole('PRODUCT_ADMIN')")
public ApiResponse<ProductResponse> create(@RequestBody @Valid CreateProductRequest req,
                                           @AuthenticationPrincipal AuthenticatedUser principal) {
    return ApiResponse.success(productAdminService.create(req, principal.memberId()));
}
```
URL 매처(`/api/admin/**` → admin 4 역할) 를 통과한 요청만 컨트롤러에 도달하므로,
`@PreAuthorize` 는 더 좁은 least-privilege 만 강제하면 된다.
