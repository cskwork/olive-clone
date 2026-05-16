# OLV-074 Recommendation

## 선택: Option A - PaymentService에 refund 통합

### 이유
1. **도메인 경계 준수**: 환불은 결제의 역연산
2. **기존 패턴과 일치**: PaymentService.confirmPayment()와 유사한 구조
3. **스키마 이미 준비됨**: V9__payment.sql의 refunds 테이블 활용

### 첫 번째 실패 테스트

```java
@Test
void approveRefund_shouldCallPG_once_and_restorePoints_andRestock() {
    // Given
    Order order = createDeliveredOrder();
    Payment payment = createApprovedPayment(order);
    Refund refund = refundService.requestRefund(
        order.getMemberId(),
        order.getOrderNo(),
        new RefundRequest("단순 변심", List.of())
    );

    // When
    RefundApprovalResult result = refundService.approveRefund(
        refund.getId(),
        ADMIN_ID
    );

    // Then
    assertThat(result.status()).isEqualTo(RefundStatus.APPPROVED);
    assertThat(order.getStatus()).isEqualTo(OrderStatus.REFUNDED);
    assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUNDED);

    // Verify PG called once
    verify(mockPgClient, times(1)).refund(any());

    // Verify inventory restocked
    // Verify points restored
    // Verify outbox event
}
```

### 구현 순서
1. Refund 엔티티 + Repository
2. RefundService 기본 골격
3. requestRefund() 구현
4. approveRefund() 구현 (PG 호출 + side effects)
5. rejectRefund() 구현
6. Controller 연결
7. PaymentService.handleRefundedWebhook 완성

### 멱등성 보장 방법
- approveRefund(): refund.status 기반 early return
- PG 호출: payment_transactions에 (payment_id, REFUND, idempotency_key) 기록
- 재고 복구: adjust()는 누적 연산이므로 멱등적
