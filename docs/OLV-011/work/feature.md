# OLV-011 — 회원 공개 인증 API

## 사용자 시나리오

신규 사용자가 이메일/비밀번호로 가입하고 즉시 로그인하면, 받은 access token 으로
보호된 자원을 호출할 수 있다. 14일짜리 refresh token 으로 access token 만료를
넘긴 뒤에도 재인증 없이 토큰을 갱신할 수 있다. 비정상 시도(5회 연속 비밀번호
실패)는 15분간 계정을 자동 잠근다.

## 엔드포인트

### POST /api/auth/signup

요청:
```json
{ "email": "alice@example.com", "password": "Pa$$word123!", "name": "Alice", "phone": "010-1234-5678" }
```
응답 201:
```json
{ "success": true, "data": { "memberId": 42 } }
```
오류:
- 400 `VALIDATION_FAILED` — 이메일 형식, 비밀번호 8자 미만, 휴대폰 형식 위반
- 409 `EMAIL_ALREADY_USED` — 이메일 중복

### POST /api/auth/login

요청:
```json
{ "email": "alice@example.com", "password": "Pa$$word123!" }
```
응답 200:
```json
{ "success": true, "data": { "accessToken": "...", "refreshToken": "...", "expiresInSec": 1800 } }
```
오류:
- 401 `BAD_CREDENTIALS` — 이메일/비밀번호 불일치
- 423 `ACCOUNT_LOCKED` — 5회 연속 실패 후 15분 잠금 / 계정 status != ACTIVE

성공/실패 모두 `member_login_histories` 에 row 1건 INSERT.

### POST /api/auth/refresh

요청:
```json
{ "refreshToken": "..." }
```
응답 200: 로그인 응답과 동일 형태. **요청한 refresh 는 즉시 revoked_at 처리되고
새 refresh 가 발급된다 (회전).** 동일 refresh 의 두 번째 호출은 401
`INVALID_REFRESH_TOKEN`.

### POST /api/auth/logout

요청 헤더: `Authorization: Bearer <accessToken>`
응답 200:
```json
{ "success": true, "data": { "revokedTokens": 3 } }
```
호출자의 모든 active refresh 를 revoke. access 는 단명(30분)이라 별도
블랙리스트 없음.

### GET /api/me

요청 헤더: `Authorization: Bearer <accessToken>`
응답 200:
```json
{ "success": true, "data": { "memberId": 42, "email": "alice@example.com", "name": "Alice", "role": "USER" } }
```

## 보안 정책

- 비밀번호: bcrypt cost 12 (`BCryptPasswordEncoder(12)`)
- Refresh 평문은 DB 에 저장되지 않음 — SHA-256 hex 64자만 (`member_refresh_tokens.token_hash CHAR(64) UNIQUE`)
- 잠금 상태는 Redis (`auth:lock:{email}` TTL 15분), 영구 컬럼 X
- 모든 LOGIN 이벤트는 `LogbackAuditLogger` 로 일자 롤링 JSON
- timing attack 완화: 알려지지 않은 이메일에 대해서도 dummy bcrypt match 호출
