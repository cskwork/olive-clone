# Failure Handling Runbooks

**Summary:** What each domain does when its external dependency fails
(PRD §15).

**Invariants & Constraints:**

- **PG outage** (PRD §15.1):
  - Order stays in `PAYMENT_PENDING`.
  - Surface a "결제 다시 시도" CTA to the user.
  - A reconciliation batch every 5 min queries the PG for unconfirmed
    `payment_key`s. If PG confirms FAIL → release reservation, set order
    `FAILED`. If PG confirms APPROVED → run the normal confirm pipeline
    (idempotent).

- **Carrier API outage** (PRD §15.2):
  - Failed waybill / status requests go to a retry queue
    (`delivery_retry_queue` table).
  - Admin back-office can trigger manual replay.
  - Delivery row stays at last known good status; never lie to the user.

- **OpenSearch outage** (PRD §15.3):
  - Search endpoint returns HTTP 503 with `{"error": "검색 일시 중단"}`.
  - Product list / category browsing keeps working (driven by Postgres /
    Redis cache).
  - Index sync events stay in the outbox until OpenSearch recovers.

- **Redis outage** (PRD §15.4):
  - Cache misses fall through to Postgres (cache-aside pattern naturally
    degrades).
  - Inventory lock falls back to `SELECT ... FOR UPDATE` on the
    `inventories` row.
  - Session-based auth: re-authenticate via JWT refresh token.

**Files of interest:**

- PRD §15.

**Decision log:**

- 2026-05-10 | seed | All four failure modes have automated tests
  (Testcontainers down-the-stack scenario) before each related ticket
  ships.

**Last updated:** 2026-05-10 by seed.
