# Delivery Domain

**Summary:** Once payment lands, delivery prepares an outbound shipment per
order (or per warehouse split). Talks to a carrier API for waybill issue
and status sync (PRD §6.9, §15.2).

**Invariants & Constraints:**

- Tables: `deliveries`, `delivery_addresses`, `delivery_status_histories`.
- Order → Delivery is **1:N** (PRD §6.9): a single order shipping from two
  warehouses splits into two deliveries.
- Delivery status enum (PRD §6.9):

  ```
  READY      : 배송 준비
  INVOICE    : 운송장 등록
  SHIPPING   : 배송중
  DELIVERED  : 배송 완료
  RETURNING  : 반품중
  RETURNED   : 반품 완료
  ```

- Carrier API failure does NOT roll back the delivery row — push the
  failed request to a retry queue and let admin manually re-process from
  the back-office (PRD §15.2). Delivery status stays at the last known
  good value.
- Status changes write to `delivery_status_histories` (parallel to
  `order_status_histories`).
- Delivery completion publishes `DeliveryCompletedEvent` so the point
  domain can flip scheduled points to spendable, and the review domain
  can mark order_items review-eligible.

**Files of interest:**

- PRD §6.9, §15.2.

**Decision log:**

- 2026-05-10 | seed | First carrier adapter is a **mock** that auto-flips
  INVOICE → SHIPPING → DELIVERED on a 5-minute scheduler for QA scenarios.

**Last updated:** 2026-05-10 by seed.
