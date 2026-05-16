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

- 2026-05-13 | OLV-140 | `@Async` + `@TransactionalEventListener(phase=AFTER_COMMIT)`는
  테스트 환경에서 즉시 실행되지 않음 (별도 스레드 풀 사용). E2E 테스트에서는
  `Awaitility.await()`로 대기하거나, 테스트 안정성을 위해 직접 DB 조작으로 우회.
- 2026-05-10 | seed | Outbox table = `outbox_events
  (id, aggregate_type, aggregate_id, event_type, payload_json, status,
  created_at, processed_at)`.
- 2026-05-10 | seed | Drainer = `@Scheduled(fixedDelay = 1000)` for now;
  swap for Debezium / Kafka Connect when traffic warrants.
- 2026-05-11 | OLV-100 | Flyway V8로 `outbox_events` 실제 생성. seed 스키마 +
  `attempt_count INT`, `dlq BOOLEAN`, `last_error TEXT` 3 컬럼 추가 — DLQ + 재시도
  메타데이터. status는 PENDING/IN_PROGRESS/DONE/FAILED 4 상태. 드레이너 스캔용
  부분 인덱스 `(status='PENDING', dlq=false)` 1개, DLQ 어드민용 `(dlq=true)` 1개.
- 2026-05-11 | OLV-100 | 첫 도메인 사용처는 `PRODUCT_INDEX_SYNC` (검색 인덱스).
  같은 트랜잭션에서 `OutboxEventRepository.save(...)`로 enqueue — at-least-once.
  드레이너는 `@Lock(PESSIMISTIC_WRITE)` + `jakarta.persistence.lock.timeout=-2`로
  Postgres `SELECT FOR UPDATE SKIP LOCKED` 동작. 멀티 인스턴스 안전.
- 2026-05-11 | OLV-100 | 드레이너는 claim(IN_PROGRESS) → 외부 호출 → finalize
  (DONE 또는 attempt++) 2-단계 트랜잭션. 워커 충돌 시 IN_PROGRESS row가 남을 수
  있음 — 별도 reaper는 follow-up.
- 2026-05-11 | OLV-100 | `@EnableScheduling`은 `SchedulingConfig` (`@Profile
  ("!test")`)로 분리. 다른 SpringBootTest 컨텍스트가 outbox row를 가로채는
  test interference 회피. 테스트는 `worker.drainOnce()`를 수동 호출.
- 2026-05-13 | OLV-110 | `DomainEventSubscribers`로 모든 도메인 이벤트 구독자 통합 완료.
  PaymentApproved → NotificationService.sendOrderConfirmed, SalesAggregator.recordSale.
  DeliveryCompleted → PointService.flipScheduledToSpendable, ReviewEligibilityCache.markEligible.
  OrderCanceled/OrderRefunded → NotificationService.sendCancellation, SalesAggregator.recordReversal.
  `OutboxEventIntegrationTest` 7개 테스트로 모든 AC 검증 완료.

**Last updated:** 2026-05-13 by OLV-140.
