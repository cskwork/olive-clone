# OLV-005 — HTTP 응답 매트릭스

MockMvc 가 캡처한 실제 응답으로 Acceptance Criteria 4 건과 hierarchy/edge case 5 건을
검증한다 (`SecurityFilterChainIT`). 모든 응답은 `ApiResponse` 봉투 (OLV-004 결정).

## Baseline (As-Is, OLV-004 시점)

| Method | URL | Auth | Status | 응답 |
|---|---|---|---|---|
| GET  | `/actuator/health` | (none) | 200 | `{"status":"UP"}` |
| GET  | `/api/cart`        | (none) | 401 | empty body (Spring 기본 403/401) |
| POST | `/api/admin/products` | (none) | 401 | empty body |
| POST | `/api/auth/login`  | (none) | 401 | empty body |

요지: `/api/auth/**` 와 `/api/admin/**` 분기 부재, 401/403 envelope 부재.

## To-Be (이번 티켓)

### AC-1: `GET /actuator/health` (no auth) → 200

```
HTTP/1.1 200
Content-Type: application/vnd.spring-boot.actuator.v3+json
{"status":"UP"}
```

### AC-2: `GET /api/cart` (no auth) → 401 + envelope

```
HTTP/1.1 401
Content-Type: application/json
{
  "success": false,
  "error": {
    "code": "AUTHENTICATION_REQUIRED",
    "message": "인증이 필요합니다.",
    "path": "/api/cart",
    "traceId": "<uuid v4>"
  }
}
```

### AC-3: `GET /api/cart` (USER token) → 200

```
HTTP/1.1 200
Content-Type: application/json
{
  "success": true,
  "data": { "memberId": 42, "items": [] }
}
```

### AC-4-USER: `POST /api/admin/products` (USER token) → 403

```
HTTP/1.1 403
{
  "success": false,
  "error": {
    "code": "ACCESS_DENIED",
    "message": "접근 권한이 없습니다.",
    "path": "/api/admin/products",
    "traceId": "<uuid>"
  }
}
```

### AC-4-PRODUCT_ADMIN: `POST /api/admin/products` (PRODUCT_ADMIN) → 201

```
HTTP/1.1 201
{
  "success": true,
  "data": { "createdBy": 7, "role": "PRODUCT_ADMIN" }
}
```

## Hierarchy / edge case

| Test | Auth | Expected | 실측 |
|---|---|---|---|
| SUPER_ADMIN ⊇ PRODUCT_ADMIN | SUPER_ADMIN | 201 | ✓ |
| CS_MANAGER URL 통과, method 막힘 | CS_MANAGER | 403 | ✓ |
| `/api/auth/login` permitAll | (none) | 200 | ✓ |
| 위조된 토큰 (서명 변조) | tampered | 401 | ✓ |

전체 9 케이스 GREEN — `build/test-results/test/TEST-com.olive.commerce.common.security.SecurityFilterChainIT.xml`.
