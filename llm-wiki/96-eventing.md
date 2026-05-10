# Eventing & Outbox Pattern

**Summary:** Async post-order work runs as Spring `ApplicationEvent`s today
and migrates to Kafka when the team scales (PRD §12, §18.3). The outbox
table is the durability mechanism in between.

**Invariants & Constraints:**

- Canonical events (PRD §12.2):

  ```
  OrderCreatedEvent
  PaymentApprovedEvent
  OrderCanceledEvent
  InventoryReservedEvent
  InventoryReleasedEvent
  ProductUpdatedEvent
  DeliveryStartedEvent
  DeliveryCompletedEvent
  ReviewCreatedEvent
  ```

- Producer pattern (mandatory): inside the same DB transaction, **insert
  to `outbox_events`**. After commit, a `@TransactionalEventListener
  (phase = AFTER_COMMIT)` publishes via `ApplicationEventPublisher`. This
  guarantees an event is published iff the source state was committed.
- Consumers must be **idempotent** — outbox drainer or Kafka rebalance
  can deliver twice. Use the event's `eventId` as the dedup key per
  consumer.
- `PaymentApprovedEvent` triggers (PRD §12.3):
  - 알림 서비스 (mock now): 주문 완료 메시지 발송.
  - 배송 서비스: 배송 준비 row 생성.
  - 포인트 서비스: 적립 예정 `point_histories` row 생성.
  - 통계 서비스: 매출 집계 데이터 업데이트.

**Files of interest:**

- PRD §12, §18.3.

**Decision log:**

- 2026-05-10 | seed | Outbox table = `outbox_events
  (id, aggregate_type, aggregate_id, event_type, payload_json, status,
  created_at, processed_at)`.
- 2026-05-10 | seed | Drainer = `@Scheduled(fixedDelay = 1000)` for now;
  swap for Debezium / Kafka Connect when traffic warrants.

**Last updated:** 2026-05-10 by seed.
