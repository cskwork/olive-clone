# OLV-062 Explore: Domain Brief

## 무엇
회원이 자신의 주문을 취소하는 사용자 API와 관리자가 임의의 주문을 강제 취소하는 관리자 API를 구현합니다.

## 왜
- 사용자 경험: 결제 후 즉시 취소는 일반적인 시나리오 (약간의 수학: 전자상거래에서 10~15%의 주문이 1시간 이내 취소됨)
- 관리자 도구: 분쟁, 환불, 배송 지연 대응을 위한 수동 오버라이드 필요

## As-Is → To-Be
- As-Is: Order 도메인은 생성 파이프라인만 구현됨 (OLV-061). 취소 API 없음.
- To-Be: `POST /api/orders/{orderNo}/cancel` (사용자)와 `POST /api/admin/orders/{orderId}/cancel` (관리자) 엔드포인트로 주문 취소 처리.

## 의존 확인

### 완료된 의존 (OLV-061)
- `Order.toCanceled()` 상태 전이 메서드 ✅
- `OrderStatusHistory` 감사 로그 ✅
- `InventoryService.release()` 예약 해제 ✅
- `CouponService.restore()` 쿠폰 복구 ✅
- `PointService.cancel()` 포인트 복구 ✅
- `OutboxEvent` 이벤트 발행 ✅

### 미완료된 의존 (OLV-072 - Payment 도메인)
- `PaymentService.cancelPG()` PG사 결제 취소
- 현재: `payment/` 패키지는 package-info.java만 존재

## 상태 전이 규칙 (Order 엔티티)

```java
// 기존 validateTransition() 로직
case PAYMENT_PENDING -> to == CANCELED  // ✅ 허용
case PAID -> to == CANCELED             // ✅ 허용
case PREPARING -> to == CANCELED        // ✅ 허용
case SHIPPING -> to == CANCELED         // ❌ 사용자 불가, 관리자만 가능
case DELIVERED -> to == CANCELED        // ❌ 불가 (환불 흐름으로)
case CANCELED, REFUNDED, FAILED -> false;  // Terminal states
```

## 취소 시나리오별 하위 도메인 호출

### 1. PAYMENT_PENDING 상태 취소
- PG 호출 없음 (결제 전)
- 재고 해제: `InventoryService.release(orderId, "주문 취소")`
- 쿠폰/포인트: 사용되지 않음 (생성 시나리오에서 status=PAID 전에 사용됨)

### 2. PAID 상태 취소 (사용자)
- PG 취소: `PaymentService.cancelPG(paymentKey, amount)` (OLV-072)
- 재고 해제: `InventoryService.release(orderId, "주문 취소")`
- 쿠폰 복구: `CouponService.restore(memberCouponId, orderId)`
- 포인트 복구: `PointService.cancel(memberId, orderId)`
- 상태 전이: `order.toCanceled(reason)`

### 3. PREPARING 상태 취소 (사용자)
- PAID와 동일하지만, 이미 재고가 commit되었을 수 있음
- 재고 해제는 여전히 필요 (예약이 commit되면 release는 no-op)

### 4. SHIPPING 상태 취소 (관리자만)
- PG 취소: `PaymentService.cancelPG(paymentKey, amount)`
- 재고: 이미 commit되었으므로 해제 불가 (반품 흐름 필요)
- 쿠폰/포인트: 복구 필요

## 멱등성 설계

취소는 멱등적이어야 합니다 (AC4). 접근 방법:

1. **상태 기반 멱등성**: 이미 CANCELED 상태면 200 반환
2. **이력 기반 멱등성**: `order_status_histories`에서 CANCELED 전이 기록 확인

```java
// 멱등성 체크 로직
if (order.getStatus() == Order.OrderStatus.CANCELED) {
    return; // 이미 취소됨
}
```

PG 취소 멱등성은 PG사가 제공하는 idempotency key를 활용해야 합니다 (OLV-072에서 구현 예정).

## 관리자 권한 검증

`MemberRole.ORDER_ADMIN`이 필요합니다. Spring Security의 `@PreAuthorize("hasRole('ORDER_ADMIN')")`를 활용합니다.

## API 설계

### 사용자 취소
```
POST /api/orders/{orderNo}/cancel
Request: { "reason": "단순 변심" } (optional)
Response: 200 OK
Body: { success: true, data: { orderId, orderNo, status: "CANCELED" } }
```

### 관리자 강제 취소
```
POST /api/admin/orders/{orderId}/cancel
Request: { "reason": "고객 요청 (배송 지연)" } (required)
Response: 200 OK
Body: { success: true, data: { orderId, orderNo, status: "CANCELED" } }
```

## Error Codes (추가 필요)

| ErrorCode | HTTP Status | 사용 시나리오 |
|-----------|-------------|--------------|
| ORDER_NOT_CANCELLABLE | 422 | SHIPPING/DELIVERED 상태에서 사용자 취소 시도 |
| ORDER_NOT_FOUND | 404 | orderNo로 주문 조회 실패 |
| ORDER_NOT_OWNED | 403 | 다른 회원의 주문 취소 시도 |

## 파일 참조

- `src/main/java/com/olive/commerce/order/Order.java:139-143` — `toCanceled()` 메서드
- `src/main/java/com/olive/commerce/order/Order.java:162-173` — `isValidTransition()` 상태 전이 규칙
- `src/main/java/com/olive/commerce/order/OrderStatusHistory.java` — 감사 로그 엔티티
- `src/main/java/com/olive/commerce/inventory/InventoryService.java:260-296` — `release()` 메서드
- `src/main/java/com/olive/commerce/promotion/CouponService.java:323-340` — `restore()` 메서드
- `src/main/java/com/olive/commerce/promotion/PointService.java:119-152` — `cancel()` 메서드
- `src/main/java/com/olive/commerce/member/MemberRole.java` — 역할 계층 구조

## 결정 로그

- 2026-05-12 | OLV-062 | PG 취소는 OLV-072에서 구현되므로, 이 티켓에서는 Mock PaymentService를 사용하여 취소 파이프라인을 구현합니다.
- 2026-05-12 | OLV-062 | 관리자 취소는 orderId(PK)로, 사용자 취소는 orderNo(사용자 노출용)로 식별합니다.
- 2026-05-12 | OLV-062 | 취소 사유는 선택적으로 order_status_histories.reason에 저장합니다.
