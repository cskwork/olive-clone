# Product Domain

**Summary:** Owns sellable items, options, brands, categories, images, and
on-sale state machine (PRD §6.2, §7.2-7.3).

**Invariants & Constraints:**

- Tables: `products`, `product_options`, `product_images`, `brands`,
  `categories`, `product_category_mapping` (M:N — a product can belong to
  multiple categories).
- Product status enum (PRD §6.2):

  ```
  DRAFT     : 임시 저장
  ON_SALE   : 판매중
  SOLD_OUT  : 품절
  STOPPED   : 판매중지
  HIDDEN    : 사용자 화면에서 숨김
  ```

- **Product and option are separate tables** (PRD §6.2, §20.3) — a single
  product can have many options (color, volume, set composition); inventory
  is per-option, not per-product.
- Money: `base_price` and `sale_price` in `DECIMAL(12,2)`. Always
  `BigDecimal` in Java code, never `double` / `float`.
- Images live in object storage; only the URL goes in `product_images`
  (PRD §6.2). Use presigned uploads from the admin UI.
- Mutating product price or status emits `ProductUpdatedEvent` →
  search-index outbox + cache invalidation.

**Files of interest:**

- PRD §6.2, §7.2, §7.3.

**Decision log:**

- 2026-05-10 | seed | Inventory keyed by `product_option_id`, never `product_id`.
- 2026-05-10 | seed | Option-less products still get one synthetic
  `product_options` row to keep the inventory join uniform.

**Last updated:** 2026-05-10 by seed.
