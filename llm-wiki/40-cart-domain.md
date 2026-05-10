# Cart Domain

**Summary:** Represents purchase intent before the user commits to an order
(PRD §6.4). The cart is convenience storage — never the source of truth at
checkout.

**Invariants & Constraints:**

- Tables: `carts`, `cart_items`. Both keyed by `member_id` for logged-in
  users; anonymous carts live in Redis under `cart:anon:{session_id}`.
- On login, merge anonymous cart into the member cart, deduplicating by
  `product_option_id` (sum quantities, cap at `available_quantity`).
- **Critical**: at order creation we **re-validate** price and inventory
  from the source domains — never trust cart-stored values (PRD §6.4,
  §4.1 장바구니).
- Cart endpoints (PRD §8.2):
  - `POST /api/cart/items` — add `{productOptionId, quantity}`.
  - `GET /api/cart` — list with current price, on-sale flag, stock state.
  - `PATCH /api/cart/items/{cartItemId}` — change quantity.
  - `DELETE /api/cart/items/{cartItemId}` — remove.

**Files of interest:**

- PRD §6.4, §8.2.

**Decision log:**

- 2026-05-10 | seed | Anonymous cart in Redis with 30-day TTL.

**Last updated:** 2026-05-10 by seed.
