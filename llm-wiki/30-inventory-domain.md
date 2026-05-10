# Inventory Domain

**Summary:** Tracks per-option sellable quantity. Order creation **reserves**
inventory; payment confirmation **commits** the reservation; payment
failure or reservation expiry **releases** it (PRD §6.7, §20.5).

**Invariants & Constraints:**

- Tables: `inventories` (one row per `product_option_id`),
  `inventory_histories` (every change is appended), `inventory_reservations`
  (per-order pending claim).
- `inventories` columns: `total_quantity`, `reserved_quantity`,
  `available_quantity` = `total_quantity − reserved_quantity` (always ≥ 0,
  enforced by service logic + DB CHECK).
- **Reserve-then-commit is mandatory** (PRD §6.7 방식 2, §20.5):
  1. Order create → reserve N units, set TTL (e.g., 15 min).
  2. Payment APPROVED → commit (decrement `total_quantity`, decrement
     `reserved_quantity`, delete reservation).
  3. Payment FAILED / TTL expired → release (decrement `reserved_quantity`).
- **Concurrency**: high-traffic SKUs need locking (PRD §10.2):
  - **Default**: Redis distributed lock (`Redisson` `RLock`, key =
    `lock:inv:{product_option_id}`, lease 5s, max wait 2s).
  - **Fallback** when Redis is down: `SELECT ... FOR UPDATE` on the
    `inventories` row inside a transaction (PRD §15.4).
- Every reservation/commit/release writes to `inventory_histories` with
  `(reason, order_id, delta, ts)` so the reservation ledger is auditable.
- Reservation TTL expiry runs as a `@Scheduled` batch every 5 minutes
  (PRD §17.2).

**Files of interest:**

- PRD §6.7, §10, §15.4, §17.2, §20.5.

**Decision log:**

- 2026-05-10 | seed | Reservation TTL = 15 minutes (longer than typical PG
  approval window, short enough to keep popular SKUs flowing).
- 2026-05-10 | seed | Lock library = Redisson (mature `RLock` semantics).

**Last updated:** 2026-05-10 by seed.
