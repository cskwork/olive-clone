# OLV-011 Explore Notes

## 도메인 / 인프라 스냅샷 (이미 깔린 것)

- `members` / `member_grades` / `member_addresses` / `member_login_histories` /
  `member_refresh_tokens` 5 테이블 (V2__member.sql, OLV-010)
- `member_refresh_tokens.token_hash CHAR(64) UNIQUE` — SHA-256 hex 강제,
  partial index `(member_id) WHERE revoked_at IS NULL`
- `member_login_histories.failure_reason VARCHAR(50)` 자유 문자열 —
  `BAD_CREDENTIALS | ACCOUNT_LOCKED | UNKNOWN_EMAIL` 가이드 (CHECK 제약 X)
- `JwtTokenProvider#issueAccess(memberId, role) / issueRefresh(memberId)` —
  refresh JWT 평문 반환, 호출자가 SHA-256 후 영속
- `JwtAuthenticationConverter` 가 access JWT → `AuthenticatedUser(memberId, role)`
- `RedisAutoConfiguration` → `StringRedisTemplate` 단일 빈 (RedisConfig 빈
  정의 X — 충돌 회피, OLV-003 학습)
- `BCryptPasswordEncoder` 빈은 아직 없음 — `spring-security-crypto`는 starter
  의존에 포함되므로 그냥 만들면 된다
- `BusinessException(ErrorCode, detail)` + `GlobalExceptionHandler` 가 envelope
  자동화. 신규 ErrorCode 추가만 하면 핸들러 변경 불필요
- `AuditLogger` 인터페이스 + `LogbackAuditLogger` 빈 — `auditLogger.log("LOGIN_SUCCESS", Map.of(...))`

## 신규 ErrorCode 후보

| code | http | 사용처 |
|------|------|--------|
| EMAIL_ALREADY_USED | 409 | signup duplicate |
| BAD_CREDENTIALS | 401 | login wrong password |
| ACCOUNT_LOCKED | 423 | login when Redis lock active |
| INVALID_REFRESH_TOKEN | 401 | refresh: not found / revoked / expired / wrong typ |

→ ErrorCode enum 에 추가. ACCOUNT_LOCKED 는 423 Locked 가 RFC 의미적
   (Lock = 일시 잠금) 가장 정확. 다만 모바일 클라이언트는 401 fallback
   처리가 더 단순 — 일단 423 채택, 클라이언트 합의되면 401 변경 가능.

## Redis 잠금 정책 (티켓 §Hints)

- key: `auth:fail:{email}` — 실패 카운트, TTL 600s
- key: `auth:lock:{email}` — 락 마커, TTL 900s
- 5회 연속 실패 → `auth:lock:` SET NX, EX 900
- 정상 로그인 시 `auth:fail:{email}` DEL
- `auth:lock:{email}` 가 있으면 비밀번호 검증 전에 `ACCOUNT_LOCKED` 반환
  (실패 카운트도 증가 X — DDoS 방지)

## Refresh 회전 정책

- 회전 시 트랜잭션:
  1. 입력 token → SHA-256 hex
  2. `SELECT ... FROM member_refresh_tokens WHERE token_hash = :h FOR UPDATE`
  3. row 없거나 `revoked_at IS NOT NULL` → `INVALID_REFRESH_TOKEN`
  4. `expires_at <= now()` → `INVALID_REFRESH_TOKEN`
  5. UPDATE revoked_at = now() (구 token)
  6. 새 access + refresh 발급, 새 refresh hash INSERT
- 동일 토큰 두 번째 사용 시 `revoked_at IS NOT NULL` 으로 막힘 (replay 차단)
- 동시 사용 race: row lock 이 직렬화 → 두 번째 호출이 revoked 보고 401

## Logout 정책

- 인증된 호출자 (access token) 의 모든 active refresh 토큰을 revoke
  `UPDATE member_refresh_tokens SET revoked_at = now() WHERE member_id = :id AND revoked_at IS NULL`
- access 토큰은 단명 (30분) 이므로 별도 블랙리스트 X — 만료 대기

## /api/me placeholder

- AC.1 검증용. `GET /api/me` 가 `{memberId, email, name, role}` 반환.
- SecurityConfig 의 `requestMatchers("/api/**").hasRole("USER")` 가 자동 커버.
- 영구 컨트롤러로 두기에는 회원 프로필 조회 경로 (별도 티켓) 와 충돌 우려 →
  `MemberProfileController` 로 두되, **현재는 단순 echo**, 후속 티켓이 확장.

## 기존 placeholder 정리

- `AuthPlaceholderController` 의 `POST /api/auth/login` 는 본 티켓의 진짜
  컨트롤러로 대체 (파일 삭제). `SecurityFilterChainIT` 의
  `apiAuth_login_isPublic_returns200` 테스트는 placeholder 응답
  `{"status": "placeholder"}` 를 검증하므로 깨질 것 — 테스트 자체를
  새 동작 (400 validation fail 또는 401 BAD_CREDENTIALS) 으로 갱신해야 함.
  → 이 변경은 OLV-005 보안 골격 IT 의 의도 (login 경로가 permitAll)와
  일치하므로 정당한 대체.

## 외부 참고

- Spring Security `BCryptPasswordEncoder` 의 default cost = 10 — 12 명시 필수
- `MessageDigest.getInstance("SHA-256")` 으로 hex 변환 — JDK 표준,
  외부 라이브러리 불필요
- Resource Server 의 `JwtDecoder` 는 access 만 검증 — refresh 토큰은
  엔드포인트 본문으로 받아서 우리가 직접 nimbus 로 파싱해야 한다 (현재
  `parseAccess` 만 있음 → `parseRefresh` 필요)
