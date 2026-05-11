# OLV-072 Domain Brief

## 1. Payment Confirm API 개요 (PRD §8.4)

`POST /api/payments/confirm`은 시스템에서 가장 중요한 돈 건드리는 메서드입니다.
PG(Payment Gateway) 승인 후 주문 상태를 PAID로 변경하고, 재고/쿠폰/포인트를 확정합니다.

### Request Body
```json
{
  "orderNo": "ORD202605100001",
  "paymentKey": "pg_payment_key",
  "amount": 35000
}
```

### Header
```
Idempotency-Key: <UUID> (선택 but 권장)
```

## 2. 8단계 파이프라인 (PRD §8.4)

| Step | 설명 | 실패 시 동작 |
|------|------|-------------|
| 1 | 주문 조회 (orderNo → 404 if missing, 422 if status ≠ PAYMENT_PENDING) | 실패 시 404/422 반환 |
| 2 | 결제 금액 검증 (payments.requested_amount == request.amount == orders.final_payment_amount) | 불일치 시 422 PAYMENT_AMOUNT_MISMATCH + audit log |
| 3 | PG사 승인 API 호출 (PgClient.confirmPayment) | PgTimeoutException → 504, FAILED → payments.status=FAILED |
| 4 | 주문 PAID 변경 + payments.status=APPROVED | |
| 5 | 재고 선점 확정 (InventoryService.commit) | |
| 6 | 쿠폰 사용 처리 (CouponService.markUsed) | |
| 7 | 포인트 사용 처리 (PointService.use + earnScheduled) | |
| 8 | PaymentApprovedEvent 발행 (outbox + AFTER_COMMIT) | |

## 3. Idempotency 규칙 (PRD §20.4)

1. **Idempotency-Key 헤더 있는 경우**:
   - `payment_transactions` 테이블에서 `(payment_id, kind=APPROVE, idempotency_key)` 검색
   - 존재하면 캐시된 응답 반환, PG 재호출 금지

2. **Idempotency-Key 헤더 없는 경우**:
   - 이미 PAID 상태인 주문에 대해 200 + 원래 paymentKey 반환 (오류 아님)

3. **DB 제약 활용**:
   - `uq_payment_transaction_replay(payment_id, kind, idempotency_key)` UNIQUE 제약이
     재시도 보호를 보장함

## 4. 실패 시나리오별 동작

| 시나리오 | HTTP 상태 | 주문 상태 | Payment 상태 | Side effects |
|----------|-----------|-----------|--------------|--------------|
| Amount mismatch | 422 | PAYMENT_PENDING | REQUESTED (변화 없음) | Audit log 작성 |
| PG returns FAIL | 200 (PRD §9.3) | PAYMENT_PENDING | FAILED | Reservation 유지 (배치에서 해제) |
| PG timeout | 504 | PAYMENT_PENDING | REQUESTED (변화 없음) | 재시도 가능 |

## 5. 존재하는 인프라 (OLV-061, OLV-071)

### 이미 구현된 것
- **V9__payment.sql**: payments, payment_transactions, refunds 테이블
- **PgClient 인터페이스**: confirmPayment() 메서드
- **MockPgClient**: behaviour 필드로 approve/fail/timeout 제어
- **PgTimeoutException**: 타임아웃 시 발생
- **ErrorCode**: PAYMENT_AMOUNT_MISMATCH, PG_TIMEOUT, PG_FAILED, IDEMPOTENCY_CONFLICT
- **InventoryService.commit(Long orderId)**: 재고 확정
- **CouponService.markUsed(Long memberCouponId, Long orderId)**: 쿠폰 사용
- **PointService.use(Long memberId, BigDecimal amount, Long orderId)**: 포인트 사용
- **PointService.earnScheduled(...)**: 포인트 적립 예약
- **OutboxEvent**: outbox_events 테이블 엔티티

### 구현해야 할 것
- Payment 엔티티 (JPA)
- PaymentRepository
- PaymentTransaction 엔티티 (JPA)
- PaymentTransactionRepository
- PaymentService.confirmPayment()
- PaymentController
- PaymentApprovedEvent

## 6. 참조: 인수테스트 패턴

OLV-061 OrderCreationApiIT와 OLV-062 OrderCancelApiIT의 패턴을 따라:
1. Testcontainers로 PostgreSQL 기동
2. Given: member, product, order, payment(REQUESTED) 생성
3. When: POST /api/payments/confirm
4. Then:
   - orders.status = PAID
   - payments.status = APPROVED
   - inventory reservation COMMITTED
   - coupon USED
   - points used + earned scheduled
   - outbox event PAYMENT_APPROVED
