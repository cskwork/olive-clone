# OLV-051: 쿠폰 관리자 API 및 회원 쿠폰 기능

## 개요

쿠폰 생성, 대량 발급, 회원 쿠폰 목록 조회, 쿠폰 검증/사용/복구 기능을 구현했습니다.

## Admin API

### 쿠폰 생성
```bash
POST /api/admin/coupons
Authorization: Bearer <PRODUCT_ADMIN_TOKEN>
Content-Type: application/json

{
  "name": "3000원 할인 쿠폰",
  "discountType": "FIXED_AMOUNT",
  "discountValue": 3000,
  "minOrderAmount": 10000,
  "startedAt": "2026-05-11T00:00:00Z",
  "endedAt": "2026-06-11T00:00:00Z",
  "maxIssueCount": 1000
}
```

### 쿠폰 목록 조회
```bash
GET /api/admin/coupons
Authorization: Bearer <PRODUCT_ADMIN_TOKEN>
```

### 쿠폰 상태 변경
```bash
PATCH /api/admin/coupons/{id}/status
Authorization: Bearer <PRODUCT_ADMIN_TOKEN>
Content-Type: application/json

{
  "status": "INACTIVE"
}
```

### 대량 발급
```bash
POST /api/admin/coupons/{id}/issue
Authorization: Bearer <PRODUCT_ADMIN_TOKEN>
Content-Type: application/json

{
  "memberIds": [1, 2, 3, 4, 5]
}
```

응답:
```json
{
  "success": true,
  "data": {
    "successCount": 5,
    "failedCount": 0,
    "failedMemberIds": []
  }
}
```

## User API

### 내 쿠폰 목록
```bash
GET /api/me/coupons?status=ISSUED
Authorization: Bearer <USER_TOKEN>
```

## Service Methods (OLV-061 주문 생성에서 호출)

### 쿠폰 검증
```java
ValidatedCoupon validated = couponService.validate(memberId, memberCouponId, orderAmount);
```

### 쿠폰 예약 (주문 생성 시)
```java
TryReserveResult result = couponService.tryReserve(memberId, memberCouponId, cartTotal);
if (result.success()) {
    // 주문 생성 진행
    ValidatedCoupon coupon = result.validatedCoupon();
} else {
    // result.failureReason()로 실패 사유 처리
}
```

### 쿠폰 사용 (주문 생성 트랜잭션 내)
```java
couponService.markUsed(memberCouponId, orderId);
```

### 쿠폰 복구 (주문 취소 시)
```java
couponService.restore(memberCouponId, orderId);  // idempotent
```

## 동시성 제어

대량 발급 시 `SELECT FOR UPDATE`로 쿠폰 행을 잠금하여 `issued_count` 갱신의 원자성을 보장합니다:
- 1000명 요청 + max_issue_count=500 → 정확히 500건만 발급
- 두 스레드 동시 요청 → max_issue_count 초과하지 않음
- 이미 발급된 회원은 건너뜀 (부분 유니크 인덱스)
