# OLV-005 Implementation — 파일별 의도

## production (신규)

| 파일 | 의도 |
|---|---|
| `member/MemberRole.java` | USER/CS_MANAGER/PRODUCT_ADMIN/ORDER_ADMIN/SUPER_ADMIN enum + `authority()` (`ROLE_<name>`). |
| `common/security/JwtTokenProvider.java` | 인터페이스 — issueAccess/issueRefresh/parseAccess. 도메인은 이 빈만 import. |
| `common/security/NimbusJwtTokenProvider.java` | RS256 발급/검증, claim(sub/role/typ/iat/exp/iss, refresh 시 jti). 시그니처 검증 실패·만료·typ 불일치를 모두 `JwtValidationException` 으로 던진다. |
| `common/security/JwtClaims.java` | parseAccess 결과 record. |
| `common/security/JwtValidationException.java` | RuntimeException — 토큰 처리 단일 경로 예외. |
| `common/security/JwtProperties.java` | `@ConfigurationProperties("olive.security.jwt")` record + bean validation. |
| `common/security/JwtConfig.java` | RSA 키 로드, Clock UTC 빈, JwtTokenProvider 빈, Resource Server `JwtDecoder`(issuer 검증 추가). |
| `common/security/RsaKeyLoader.java` | PKCS#8(private), X.509(public) PEM 디코더. 마커 부재 시 즉시 fail. |
| `common/security/AuthenticatedUser.java` | `@AuthenticationPrincipal` 으로 컨트롤러가 받는 record. |
| `common/security/JwtAuthenticationConverter.java` | Resource Server `Jwt → UsernamePasswordAuthenticationToken<AuthenticatedUser>`. typ != access 또는 role 누락 시 `InvalidBearerTokenException` → 401. |
| `common/security/JwtAuthenticationEntryPoint.java` | 401 응답. ObjectMapper 로 ApiResponse.failure(AUTHENTICATION_REQUIRED) 직접 직렬화. traceId 는 MDC 에서. |
| `common/security/JwtAccessDeniedHandler.java` | 403 응답. ApiResponse.failure(ACCESS_DENIED). |
| `common/security/RoleHierarchyConfig.java` | `RoleHierarchyImpl.fromHierarchy("...")` 5단계 + `@EnableMethodSecurity` + `DefaultMethodSecurityExpressionHandler`. URL 매처는 Spring 6 자동 적용. |
| `cart/CartPlaceholderController.java` | `/api/cart` GET — `@AuthenticationPrincipal` 풀어 200 응답. OLV-040 이 본 컨트롤러를 대체. |
| `admin/AdminProductPlaceholderController.java` | `/api/admin/products` POST `@PreAuthorize PRODUCT_ADMIN` — 201 응답. OLV-022 가 대체. |
| `auth/AuthPlaceholderController.java` | `/api/auth/login` POST — placeholder 응답. permitAll 검증용. OLV-011 이 대체. |

## production (수정)

| 파일 | 변경 의도 |
|---|---|
| `common/config/SecurityConfig.java` | placeholder 였던 체인을 OAuth2 Resource Server + EntryPoint + AccessDeniedHandler + 분기 매처(`/actuator/health*`/`/api/auth/**`/`GET /api/products,search/**`/`/api/admin/**`/`/api/**`) 로 확장. |
| `common/error/ErrorCode.java` | `AUTHENTICATION_REQUIRED(401)`, `ACCESS_DENIED(403)` append-only. |
| `build.gradle.kts` | `spring-boot-starter-oauth2-resource-server` + `nimbus-jose-jwt:9.40` 추가. Docker Desktop on macOS 의 `docker.raw.sock` 자동 탐지로 Testcontainers 호환. |
| `src/main/resources/application.yml` | `olive.security.jwt` 블록 (issuer/access-ttl/refresh-ttl/key-locations). |
| `.gitignore` | `src/main/resources/keys/*` 제외 (.gitkeep / README 만 체크인). |

## tests (신규)

| 파일 | 검증 |
|---|---|
| `JwtTokenProviderTest.java` (6) | 단위 — round-trip, 31분 후 만료, RS256+typ/role/sub/iss claim, refresh JWT 의 typ/jti, refresh 토큰을 parseAccess 로 거부, 시그니처 위조 거부. |
| `SecurityFilterChainIT.java` (9) | 통합 — AC 4 건 + Auth permitAll + SUPER_ADMIN hierarchy + CS_MANAGER URL 통과/method 차단 + tampered token 401. |
| `src/test/resources/keys/app.{key,pub}` | 테스트용 RSA 2048 keypair (운영 키와 분리). |

## 의도적으로 *하지 않은* 것

- `member_refresh_tokens` 테이블 스키마: OLV-011 마이그레이션 책임. Provider 는 평문 JWT 만 반환.
- refresh rotation / blacklist 로직: OLV-011 회원 로그인 흐름에서 다룬다.
- 키 회전(`kid` 헤더 + JWKSource 다중 키): 단일 키만 충분 (`llm-wiki/98-security.md` 결정 로그).
- 2FA / SUPER_ADMIN 추가 인증: feature flag 뒤에서 별도 티켓.
- audit log 의 `LOGIN_SUCCESS` 등 카테고리 사용: 본 티켓은 토큰 발급기만 — 실제 호출은 OLV-011.
