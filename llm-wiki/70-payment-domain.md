# Payment Domain

**Summary:** Bridges our orders to the external PG (Toss / KCP / NaverPay
style). Manages READY → REQUESTED → APPROVED, idempotent confirms, refund,
and the PG callback as the source of truth (PRD §6.6, §8.4, §15.1, §20.4).

**Invariants & Constraints:**

- Tables: `payments`, `payment_transactions`, `refunds`.
- Payment status enum (PRD §6.6):

  ```
  READY      : 결제 준비
  REQUESTED  : 결제 요청
  APPROVED   : 결제 승인
  FAILED     : 결제 실패
  CANCELED   : 결제 취소
  REFUNDED   : 환불 완료
  ```

- **Idempotency is mandatory** (PRD §20.4): every confirm/cancel request
  carries a unique `idempotencyKey`; same key MUST yield the same result
  with no side-effect on second call. Persist the key on `payments` /
  `payment_transactions`.
- **PG callback wins** (PRD §6.6, §15.1): client-side success ≠ payment
  approved. The webhook (or our verification API call against the PG) is
  authoritative.
- **Amount must match**: confirm checks `requested_amount == orders.final_payment_amount`
  (PRD §14.4) before approving. Mismatch → reject + alert.
- We **never** persist raw card numbers (PRD §14.4): only store the PG's
  `payment_key`, `pg_provider`, `transaction_id`.
- Confirm pipeline (PRD §8.4) is:
  1. 주문 조회
  2. 결제 금액 검증
  3. PG사 결제 승인 API 호출
  4. 결제 성공 시 주문 상태 PAID 변경
  5. 재고 선점 확정 (commit reservation via inventory domain)
  6. 쿠폰 사용 처리
  7. 포인트 사용 처리
  8. 주문 완료 알림 발송 (via PaymentApprovedEvent)
- PG outage runbook (PRD §15.1): order stays PAYMENT_PENDING; user can
  retry; a batch reconciles unconfirmed payments after N minutes.

**Files of interest:**

- PRD §6.6, §8.4, §14.4, §15.1, §20.4.

**Decision log:**

- 2026-05-10 | seed | First PG adapter is a **mock** — configurable to
  return success / fail / timeout for QA. Real PG (Toss) wired later.
- 2026-05-12 | OLV-070 | Payment schema V9__payment.sql applied. 3 tables
  (payments, payment_transactions, refunds) with FK constraints, idempotency
  UNIQUE constraints, CHECK constraints for status/method/kind enums, 6 indexes.
  Replay protection via (payment_id, kind, idempotency_key) UNIQUE on
  payment_transactions. Full PG response stored as JSONB for post-mortem.
- 2026-05-12 | OLV-071 | PgClient interface + MockPgClient implementation enabled.
  Behaviour field controls approve/fail/timeout modes for QA testing without app
  restart. @ConditionalOnProperty separates PG implementations by environment.
  PgTimeoutException added. ErrorCode extended with PG_TIMEOUT/PG_FAILED/PG_WEBHOOK_INVALID.
- 2026-05-12 | OLV-073 | Webhook handler implemented. POST /api/payments/webhook with HMAC-SHA256
  signature verification (Mock PG), Redis deduplication (SETNX with 5min TTL on
  `webhook:dedup:{paymentKey}:{status}`), state-based reconciliation (APPROVED reuses
  confirm path, FAILED/CANCELED updates status only, REFUNDED validates REFUND_REQUESTED).
  Always returns 200 to allow PG retries. Webhook is authoritative source of truth
  per PRD §6.6.

**Last updated:** 2026-05-12 by OLV-073.
