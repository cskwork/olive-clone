# OLV-062 QA Evidence

## 테스트 실행 결과

```bash
./gradlew cleanTest test --tests "*.OrderCancelApiIT"
```

### 결과: BUILD SUCCESSFUL in 10s
- 9 tests completed
- 0 failures
- 0 errors

### 테스트 커버리지

| 테스트 케이스 | 상태 | 검증 내용 |
|--------------|------|----------|
| cancelOrder_fromPaymentPendingStatus_success | ✅ | PAYMENT_PENDING 상태 취소, 재고 예약 해제 |
| cancelOrder_fromPaidStatus_success | ✅ | PAID 상태 취소, 쿠폰 복구(ISSUED), 포인트 복구(CANCEL) |
| cancelOrder_fromPreparingStatus_success | ✅ | PREPARING 상태 취소 |
| cancelOrder_fromShippingStatus_returns422 | ✅ | SHIPPING 상태에서 사용자 취소 시 422 반환 |
| cancelOrder_idempotent_returns200 | ✅ | 이미 취소된 주문 재요청 시 200 OK (중복 PG 취소 없음) |
| cancelOrder_notOwned_returns403 | ✅ | 타 회원 주문 취소 시도 시 403 Forbidden |
| adminCancelOrder_fromShippingStatus_success | ✅ | 관리자 강제 취소, 감사 이력 ADMIN 기록 |
| adminCancelOrder_missingReason_returns400 | ✅ | 사유 누락 시 400 Bad Request |
| adminCancelOrder_withoutOrderAdminRole_returns403 | ✅ | 일반 사용자 관리자 엔드포인트 호출 시 403 |

## API 엔드포인트 검증

### 사용자 취소 API
```
POST /api/orders/{orderNo}/cancel
```
- 인증: 필요 (USER role)
- Request body: `{"reason": "선택 사유"}` (선택)
- Response: 200 OK with CancelOrderResponse

### 관리자 강제 취소 API
```
POST /api/admin/orders/{orderId}/cancel
```
- 인증: 필요 (ORDER_ADMIN role)
- Request body: `{"reason": "필수 사유"}` (필수)
- Response: 200 OK with CancelOrderResponse

## Acceptance Criteria 검증

- [x] User cancel from PAID: PG cancel called (mock), order status CANCELED, inventory restored, coupon ISSUED, points CANCEL
- [x] User cancel from SHIPPING: 422 Unprocessable Entity
- [x] Admin cancel from SHIPPING: succeeds, PG cancel called (mock)
- [x] Cancel is idempotent: replaying returns 200 with no second PG cancel

## 테스트 로그 샘플

```
WARN  business_exception code=ORDER_NOT_OWNED message=orderNo=ORD20260512000001 does not belong to memberId=999 path=/api/orders/ORD20260512000001/cancel
WARN  business_exception code=ORDER_NOT_CANCELLABLE message=User cannot cancel order in state: SHIPPING (use return flow) path=/api/orders/ORD20260512000007/cancel
```

## 추가 검증 필요 사항

없음. 모든 AC가 충족됨.
