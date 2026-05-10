# Promotion Domain (Coupons + Points)

**Summary:** Owns coupons, member-issued coupons, promotion campaigns
(기획전), and the point ledger (PRD §6.8).

**Invariants & Constraints:**

- Tables: `coupons`, `member_coupons`, `promotions`, `promotion_products`,
  `points`, `point_histories`.
- Discount type enum (PRD §6.8):

  ```
  FIXED_AMOUNT     : 정액 할인
  PERCENTAGE       : 정률 할인
  FREE_SHIPPING    : 무료 배송
  BUY_ONE_GET_ONE  : N+1 행사
  MEMBER_GRADE     : 회원 등급 할인
  ```

- Coupon validity is checked **at order creation** (PRD §6.8): not expired,
  not already used, satisfies `min_order_amount`, applicable to the cart's
  products.
- Coupon use is single-shot — `member_coupons.status` transitions
  `ISSUED → USED` atomically with order creation. Order cancel rolls back
  to `ISSUED`.
- Points are **ledger-based** (PRD §6.8): never store a balance on the
  member row. Always sum from `point_histories`. Reasons:
  `EARN`, `USE`, `CANCEL`, `EXPIRE`, `ADMIN_ADJUST`.
- Points earned from an order are scheduled (배송 완료 후 N일) — write a
  `point_histories` row with `available_at` in the future, batch flips it
  to spendable.

**Files of interest:**

- PRD §6.8, §17.

**Decision log:**

- 2026-05-10 | seed | Points are ledger-only, no cached balance column.

**Last updated:** 2026-05-10 by seed.
