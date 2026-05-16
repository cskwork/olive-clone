# OLV-074 Domain Brief

## 핵심 발견

### 1. Refunds 테이블 이미 정의됨 (V9__payment.sql)
```sql
CREATE TABLE refunds (
    id                  BIGSERIAL PRIMARY KEY,
    payment_id          BIGINT       NOT NULL REFERENCES payments(id),
    order_id            BIGINT       NOT NULL REFERENCES orders(id),
    amount              DECIMAL(12,2) NOT NULL CHECK (amount > 0),
    reason              VARCHAR(255),
    status              VARCHAR(30)  NOT NULL DEFAULT 'REQUESTED',
    pg_refund_key       VARCHAR(255),
    requested_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    approved_at         TIMESTAMPTZ,
    CONSTRAINT refunds_status_check
        CHECK (status IN ('REQUESTED', 'APPROVED', 'FAILED'))
);
```

### 2. 상태 전이 기존 코드와 일치
- Order 상태: `DELIVERED → REFUND_REQUESTED → REFUNDED` (Order.java:227-231)
- Payment 상태: `APPROVED → REFUNDED` (Payment.java:159)

### 3. 필요한 서비스 메서드 모두 존재
- `PgClient.refund(RefundRequest)` - MockPgClient에 이미 구현
- `InventoryService.adjust(optionId, delta, reason, adminId)` - InventoryService.java:306
- `PointService.cancel(memberId, orderId)` - PointService.java:121

### 4. 멱등성 고려사항
- refunds 테이블에 UNIQUE 제약 없음 → status 기반 멱등성 필요
- payment_transactions의 `(payment_id, kind, idempotency_key)` UNIQUE 활용 가능

### 5. 부분 환불 지원
- 단일 결제에 여러 환불 가능 (PRD 주석: "One payment can have multiple partial refunds")
- 환불 가능 금액 = `final_payment_amount - SUM(prior refunds)`
- 쿠폰/포인트 비례 계산 필요 (부분 환불 시)

### 6. PaymentService.handleRefundedWebhook TODO
```java
// TODO: refund 테이블 상태 업데이트 (OLV-090)
```
이 티켓(OLV-074)에서 webhook 경로도 완성해야 함.

## 파일 경로 참조

| 엔티티/서비스 | 경로 |
|--------------|------|
| Refund 스키마 | `src/main/resources/db/migration/V9__payment.sql:80-100` |
| PgClient | `src/main/java/com/olive/commerce/payment/client/PgClient.java:29-31` |
| MockPgClient | `src/main/java/com/olive/commerce/payment/client/MockPgClient.java:109-113` |
| Order 상태 전이 | `src/main/java/com/olive/commerce/order/Order.java:227-231` |
| InventoryService.adjust | `src/main/java/com/olive/commerce/inventory/InventoryService.java:306-328` |
| PointService.cancel | `src/main/java/com/olive/commerce/promotion/PointService.java:121-152` |
| PaymentService | `src/main/java/com/olive/commerce/payment/PaymentService.java` |
