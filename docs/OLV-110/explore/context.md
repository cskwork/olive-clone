# OLV-110 Explore: Context

## 기존 구현 확인

OLV-110 티켓의 모든 요구사항이 이미 구현되어 있음을 확인했습니다.

### 1. 아웃박스 테이블 (V8__outbox_events.sql)

- `outbox_events` 테이블이 OLV-100에서 이미 생성됨
- 스키마: id, aggregate_type, aggregate_id, event_type, payload_json, status (PENDING/IN_PROGRESS/DONE/FAILED), attempt_count, dlq, last_error, created_at, processed_at
- 인덱스: `(status, dlq, id)` WHERE status='PENDING' AND dlq=FALSE (드레이너 스캔용)

### 2. 아웃박스 인프라

- `OutboxPublisher`: 이벤트 발행 (트랜잭션 내에서 실행)
- `OutboxEventDrainer`: 1초 주기로 PENDING 이벤트를 처리하여 Spring ApplicationEvent로 발행
- `OutboxEventRepository`: JPA 리포지토리

### 3. 이벤트 클래스 (모두 존재)

- `PaymentApprovedEvent` - 결제 승인 이벤트
- `OrderCanceledEvent` - 주문 취소 이벤트
- `OrderRefundedEvent` - 환불 완료 이벤트
- `DeliveryCompletedEvent` - 배송 완료 이벤트

### 4. 구독자 서비스 (모두 구현됨)

- `NotificationService`: 알림 발송 (mock - 로그 + JSON 파일 저장)
- `SalesAggregator`: 매출 집계 (Redis 기반)
- `PointService.flipScheduledToSpendable`: 배송 완료 시 포인트 전환 (예약 → 즉시 사용 가능)
- `ReviewEligibilityCache.markEligible`: 리뷰 작성 가능 마크 (Redis 기반)

### 5. DomainEventSubscribers

모든 구독자가 `@EventListener`로 와이어링되어 있음:

```java
@Component
public class DomainEventSubscribers {
    // PaymentApprovedEvent → NotificationService.sendOrderConfirmed
    @EventListener
    public void onPaymentApproved_SendNotification(PaymentApprovedEvent event)

    // PaymentApprovedEvent → SalesAggregator.recordSale
    @EventListener
    public void onPaymentApproved_RecordSale(PaymentApprovedEvent event)

    // DeliveryCompletedEvent → PointService.flipScheduledToSpendable
    @EventListener
    public void onDeliveryCompleted_FlipPointsToSpendable(DeliveryCompletedEvent event)

    // DeliveryCompletedEvent → ReviewEligibilityCache.markEligible
    @EventListener
    public void onDeliveryCompleted_MarkReviewEligible(DeliveryCompletedEvent event)

    // OrderCanceledEvent → NotificationService.sendCancellation
    @EventListener
    public void onOrderCanceled_SendNotification(OrderCanceledEvent event)

    // OrderCanceledEvent → SalesAggregator.recordReversal
    @EventListener
    public void onOrderCanceled_RecordReversal(OrderCanceledEvent event)

    // OrderRefundedEvent → NotificationService.sendCancellation
    @EventListener
    public void onOrderRefunded_SendNotification(OrderRefundedEvent event)

    // OrderRefundedEvent → SalesAggregator.recordReversal
    @EventListener
    public void onOrderRefunded_RecordReversal(OrderRefundedEvent event)
}
```

### 6. 통합 테스트

`OutboxEventIntegrationTest.java`가 모든 AC를 커버:

- AC1: `paymentApproved_createsOutboxEvent_andProcessesWithinOneSecond`
- AC2: `paymentApproved_allSubscribersFireExactlyOnce`
- AC3: `drainerStop_doesNotLoseEvents_restartProcessesThem`
- AC4: `poisonedEvent_increasesAttemptCount_fiveFailuresMovesToDead`

## 결론

티켓의 모든 요구사항이 이미 구현되어 있습니다. 검증(QA) 단계로 넘어갑니다.
