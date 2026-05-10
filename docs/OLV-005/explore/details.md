# OLV-005 Explore — 세부 노트

## 1. PRD/위키 근거 매핑

| 산출물 | 근거 |
|---|---|
| JWT access (TTL 30m) / refresh (TTL 14d) | `llm-wiki/98-security.md:8-10`, `llm-wiki/10-member-domain.md:34-35`, PRD §14.1 |
| RS256 keypair, dev = `src/main/resources/keys/`, gitignored | 티켓 §Hints, `llm-wiki/98-security.md:36-37` (rotated keypair / Secrets Manager) |
| 역할 hierarchy `USER < CS_MANAGER < PRODUCT_ADMIN | ORDER_ADMIN < SUPER_ADMIN` | `llm-wiki/10-member-domain.md:13-21` |
| `/api/admin/**` 별 least-privilege | `llm-wiki/10-member-domain.md:19-21` (`PRODUCT_ADMIN` 은 product, `ORDER_ADMIN` 은 order) — 본 티켓은 hierarchy + entry placeholder까지 |
| `member_refresh_tokens` JTI hashed | `llm-wiki/98-security.md:9-10`, OLV-011 마이그레이션이 테이블을 만든다 — 본 티켓은 *Provider*만, 실제 persistence는 OLV-011 |
| 401/403 분리 — `GlobalExceptionHandler` rethrow → SecurityFilterChain handler | `llm-wiki/01-common-conventions.md:36`, OLV-004 결정 |

## 2. 기존 자산 / 빈 슬롯

- `build.gradle.kts:32` 이미 `spring-boot-starter-security` 포함. JWT 라이브러리(jjwt / nimbus-jose-jwt)는 부재 → 추가 필요.
- `common/config/SecurityConfig.java` — placeholder. `/actuator/health|info` permitAll, 그 외 authenticated. 체인 1개. `/api/auth/**`, `/api/products/**`, `/api/search/**`, `/api/admin/**` 분기 부재.
- `common/error/GlobalExceptionHandler.java:53-56` — `AuthenticationException`/`AccessDeniedException`를 명시적으로 rethrow → SecurityFilterChain의 `AuthenticationEntryPoint`/`AccessDeniedHandler`가 401/403 envelope을 만들어야 한다. EntryPoint/AccessDeniedHandler **부재** → 본 티켓에서 추가.
- `RequestIdFilter` (HIGHEST_PRECEDENCE) — Spring Security 필터보다 먼저 traceId를 MDC에 심기 때문에, 401/403 envelope에도 traceId가 채워진다.
- `application.yml` — actuator만 노출. JWT 키 경로/issuer/audience 등 설정 부재. 위키에 따라 `UserDetailsServiceAutoConfiguration` 이 exclude되어 있을 가능성 — Spring Security 자체는 starter로 살아있고, 단지 in-memory user 자동 등록만 막는 패턴.

## 3. JWT 처리 방식 결정 (Resource Server vs 커스텀 필터)

| 옵션 | 장점 | 단점 | 결정 |
|---|---|---|---|
| `spring-boot-starter-oauth2-resource-server` + RS256 JWK | Spring 공식, JwtDecoder/JwtAuthenticationConverter 자동 와이어, 표준 claim 검증 | 의존성 1개 추가, 클레임 매핑 코드 작성 필요, refresh 발급은 어차피 직접 구현 | 채택 |
| 커스텀 `OncePerRequestFilter` + jjwt 파싱 | 외부 의존 최소, 토큰 처리 경로 가시화 | 유효성 검증을 직접 구현 (exp/nbf/iss/aud) → 보안 사각지대 위험, 단위 테스트 부담 | 기각 |

근거: 티켓이 *"OAuth2 Resource Server 또는 커스텀 필터 — 하나 골라 문서화"*. Resource Server는 jose 검증을 라이브러리에 위임할 수 있어 사각지대가 적다. **발급(jjwt)** 과 **검증(resource-server)** 을 분리하면 책임이 깔끔하다.

## 4. RS256 keypair 관리

- 개발: `src/main/resources/keys/` 아래 `app.key` (PKCS#8 PEM, private), `app.pub` (X.509 PEM, public). `.gitignore` 에 추가.
- README에 `openssl genpkey -algorithm RSA -out app.key -pkeyopt rsa_keygen_bits:2048` + `openssl rsa -in app.key -pubout -out app.pub` 명령 기재.
- `application.yml` 의 `olive.security.jwt.private-key-location`, `public-key-location` 으로 외부화 — 운영에서는 Secrets Manager 또는 K8s Secret 마운트.
- 키 부재 시 startup fail-fast (Resource Server 의 `NimbusJwtDecoder` 가 키 로드 실패하면 빈 생성 단계에서 throw).

## 5. RoleHierarchy 구성

- Spring Security 6.3+ `RoleHierarchyImpl.fromHierarchy(...)` 또는 `with-default-rolePrefix(...)` 빌더 사용.
- 다이어그램:
  ```
  SUPER_ADMIN > PRODUCT_ADMIN
  SUPER_ADMIN > ORDER_ADMIN
  SUPER_ADMIN > CS_MANAGER
  PRODUCT_ADMIN > USER
  ORDER_ADMIN > USER
  CS_MANAGER > USER
  ```
- `@PreAuthorize("hasRole('PRODUCT_ADMIN')")` 가 `SUPER_ADMIN` 토큰으로도 통과한다 (티켓 §Hints).
- `MethodSecurityExpressionHandler` 빈에 hierarchy 주입 + `@EnableMethodSecurity(prePostEnabled = true)`.
- 단, 본 티켓은 `/api/admin/**` 일괄 보호만 하고 product-admin 전용 엔드포인트별 분기는 OLV-022 등에서. 다만 AC-4("`/api/admin/products` POST가 PRODUCT_ADMIN 200/USER 403")가 명시되어 있으므로 placeholder controller에 `@PreAuthorize("hasRole('PRODUCT_ADMIN')")` 를 둔다.

## 6. JwtTokenProvider API 설계

```java
public interface JwtTokenProvider {
    String issueAccess(long memberId, MemberRole role);
    String issueRefresh(long memberId);                 // 평문 JWT 반환, 호출자가 hash 후 DB 저장
    JwtClaims parseAccess(String token);                 // 검증 + 만료 검사 (resource server가 실제 사용)
}

public enum MemberRole { USER, CS_MANAGER, PRODUCT_ADMIN, ORDER_ADMIN, SUPER_ADMIN }

public record JwtClaims(long memberId, MemberRole role, Instant expiresAt) {}
```

claim 구조:
- access: `sub=<memberId>`, `role=<MemberRole.name()>`, `iat`, `exp`, `iss=olive-commerce`, `typ=access`.
- refresh: `sub=<memberId>`, `iat`, `exp`, `iss=olive-commerce`, `typ=refresh`, `jti=<UUID>`.

`AuthenticationPrincipal` 분해는 `JwtAuthenticationConverter` 가 `Jwt → UsernamePasswordAuthenticationToken(AuthenticatedUser, ...)` 형태로 변환하도록 설정. `record AuthenticatedUser(long memberId, MemberRole role)`.

## 7. 첫 실패 테스트 (TDD 진입점)

1. **`JwtTokenProviderTest`**
   - `issueAccess_roundTrip_decodesSameClaims` — `issueAccess(42L, USER)` → `parseAccess(token).memberId() == 42` && `role() == USER`.
   - `issueAccess_expired_throws` — `Clock` 주입 → 31분 뒤 시계로 parse → `JwtException` 또는 `JwtValidationException`.
   - `issueRefresh_setsTypRefresh_andHasJti` — refresh 토큰 디코딩 시 `typ=refresh` claim 존재, `jti` 가 UUID.
   - **현재 `JwtTokenProvider` 부재 → 컴파일 실패 → RED.**

2. **`SecurityFilterChainIT`** (`@SpringBootTest` + Postgres TC)
   - `actuatorHealth_isPublic_returns200`
   - `apiCart_withoutToken_returns401_andErrorEnvelope` (envelope `success=false`, `code=...`, `traceId` 채워짐)
   - `apiCart_withUserToken_returns200`
   - `apiAdminProducts_withUserToken_returns403`
   - `apiAdminProducts_withProductAdminToken_returns200or201`
   - `apiAdminProducts_withSuperAdminToken_returns200or201` (hierarchy 검증)
   - `apiAuth_login_isPublic` (POST `/api/auth/login` placeholder가 401 아님, 실제 본문 없이도 reach)

3. **placeholder 컨트롤러** (`/api/cart` GET, `/api/admin/products` POST, `/api/auth/login` POST) — 본 티켓의 AC를 검증할 수 있을 만큼만. OLV-040(cart), OLV-022(admin product), OLV-011(auth) 가 본 구현으로 대체.

## 8. 위험 및 완화

- **R1: 401/403 envelope 누락** → SecurityFilterChain에 `JwtAuthenticationEntryPoint` (401), `JwtAccessDeniedHandler` (403) 빈 명시. 둘 다 `ErrorBody`+`ApiResponse.failure(...)` JSON으로 응답. traceId는 MDC 에서 읽음 (RequestIdFilter가 SecurityFilterChain보다 먼저 동작).
- **R2: jjwt vs nimbus 충돌** — Resource Server는 nimbus-jose-jwt를 자동으로 가져온다. **발급도 nimbus**로 통일하면 의존성 1개로 끝. → `nimbus-jose-jwt` 단일 채택, jjwt 도입 안 함.
- **R3: 키 파일 없는 환경에서 부팅 실패** — 단위 테스트는 `@WebMvcTest` 로 SecurityConfig 회피. 통합 테스트는 `src/test/resources/keys/` 에 별도 keypair 두고 profile `test` 에서만 활성. dev/local 도 키 자동 생성 가이드를 README에 명시.
- **R4: `RoleHierarchyImpl.setHierarchy(String)` deprecated (6.3+)** — `RoleHierarchyImpl.fromHierarchy("...")` 정적 팩토리 사용.
- **R5: `member_refresh_tokens` 테이블 부재** — 본 티켓은 토큰 발급기만 만들고 persistence는 OLV-011. Provider API에 hash/저장 로직 X — 그저 `String issueRefresh(long memberId)` 반환만. Provider 테스트는 hash 검증을 안 한다.
- **R6: `application.yml` 권한 거부** — 빌드 환경의 deny rule로 application.yml read가 막혀있어 직접 편집 불가능. 새 yml 라인은 새 파일 (예: `application-jwt.yml`) 또는 OLV-005 산출물 디렉토리에 sample 만 두고, 실제 yml 변경은 사용자가 수동으로 머지하도록 가이드를 work/feature.md에 남긴다 → **수정 (re-evaluation)**: deny rule은 *읽기*만 막는다. 쓰기/Edit은 시도해보고, 막히면 work/feature.md 에 patch만 남기는 계획.

## 9. 산출물 디렉토리

```
src/main/java/com/olive/commerce/
  common/security/
    JwtTokenProvider.java               # interface
    NimbusJwtTokenProvider.java         # impl (nimbus-jose-jwt)
    JwtClaims.java                      # record
    JwtAuthenticationEntryPoint.java    # 401 envelope
    JwtAccessDeniedHandler.java         # 403 envelope
    AuthenticatedUser.java              # record
    RoleHierarchyConfig.java            # @Bean RoleHierarchy + MethodSecurityExpressionHandler
    JwtAuthenticationConverter.java     # Jwt → UsernamePasswordAuthenticationToken<AuthenticatedUser>
  common/config/
    SecurityConfig.java                 # 갱신 — 분기 + resource-server + entry/handler
  member/
    MemberRole.java                     # enum
  cart/
    CartPlaceholderController.java      # /api/cart GET
  admin/
    AdminProductPlaceholderController.java  # /api/admin/products POST  @PreAuthorize PRODUCT_ADMIN
  auth/
    AuthPlaceholderController.java      # /api/auth/login POST (echo, AC 용)
src/main/resources/
  keys/.gitkeep                         # README에 키 생성 가이드 수록
src/test/java/com/olive/commerce/common/security/
  JwtTokenProviderTest.java
src/test/java/com/olive/commerce/common/config/
  SecurityFilterChainIT.java
src/test/resources/keys/
  app.key, app.pub (테스트용 RSA 2048)
```

`.gitignore` 추가: `src/main/resources/keys/*` (단 `.gitkeep` 제외).

README 추가: 섹션 "JWT 키 생성".
