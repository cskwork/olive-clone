# OLV-011 Review — API 응답 형태 baseline

본 문서는 review 단계에서 검토한 4 엔드포인트 + /api/me 의 happy/edge 응답 형태를
미리 못 박는다. QA 단계의 실제 통합 테스트 출력이 본 baseline 과 일치해야 한다.

## POST /api/auth/signup

happy 201:
```json
{ "success": true, "data": { "memberId": 1 } }
```
duplicate 409:
```json
{ "success": false, "error": { "code": "EMAIL_ALREADY_USED", "message": "email=alice@example.com", "path": "/api/auth/signup", "traceId": "<uuid>" } }
```
validation 400:
```json
{ "success": false, "error": { "code": "VALIDATION_FAILED", "message": "요청 본문 검증에 실패했습니다.", "path": "/api/auth/signup", "traceId": "<uuid>", "fieldErrors": [...] } }
```

## POST /api/auth/login

happy 200:
```json
{ "success": true, "data": { "accessToken": "eyJ...", "refreshToken": "eyJ...", "expiresInSec": 1800 } }
```
wrong-password 401: `error.code = BAD_CREDENTIALS`
locked 423: `error.code = ACCOUNT_LOCKED`

## POST /api/auth/refresh

happy 200: 로그인 응답과 동일.
replay 401: `error.code = INVALID_REFRESH_TOKEN`

## POST /api/auth/logout

happy 200:
```json
{ "success": true, "data": { "revokedTokens": 2 } }
```
unauthenticated 401: `error.code = AUTHENTICATION_REQUIRED`

## GET /api/me

happy 200:
```json
{ "success": true, "data": { "memberId": 1, "email": "alice@example.com", "name": "Alice", "role": "USER" } }
```

## Review 발견 + 조치

| severity | file:line | 이슈 | 조치 |
|---|---|---|---|
| HIGH | AuthService.java:116 | dummy bcrypt 해시 형식 비정상 → matches() 가 매번 warn 로그 | `passwordEncoder.encode("__user_enumeration_dummy__")` 결과를 생성자에서 캐시 |
| MEDIUM | placeholder controller | `AuthPlaceholderController` 가 빈 파일로 남음 (rm 권한 미허용) | 다음 정리 티켓에서 삭제, 본 티켓은 매핑만 제거 |
| LOW | AuthService.java | timing-attack 완벽 방어 X (known/unknown timing 차이 잔존) | 일단 dummy hash 호출로 1차 방어, 향후 별도 강화 티켓 |
