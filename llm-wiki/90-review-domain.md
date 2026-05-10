# Review Domain

**Summary:** Customer ratings and text/image feedback for purchased items
(PRD §6.10).

**Invariants & Constraints:**

- Tables: `reviews`, `review_images`, `review_reports`.
- **Eligibility** (PRD §6.10): only members who own an `order_items` row
  for the product can write a review. Check via `EXISTS (SELECT 1 FROM
  order_items oi JOIN orders o ON ... WHERE o.member_id = ? AND
  oi.product_id = ? AND o.status IN ('DELIVERED', 'PAID'))`.
- One review per `order_item_id` to prevent duplicate reviews from a
  multi-quantity purchase.
- Aggregate fields (`avg_rating`, `review_count`) live in a Redis cache or
  a `product_review_summaries` aggregate table — never compute on the hot
  read path (PRD §6.10).
- `review_reports` allows users to flag inappropriate reviews; admin
  hide-marks them rather than deleting (preserves audit trail).

**Files of interest:**

- PRD §6.10.

**Decision log:**

- 2026-05-10 | seed | Aggregate reviews into `product_review_summaries`
  table updated by `ReviewCreatedEvent` subscriber.

**Last updated:** 2026-05-10 by seed.
