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

- 2026-05-11 | OLV-021 | Flyway V3__product.sql inserts demo data: 1 brand (더샘),
  3 categories (스킨케어, 메이크업, 헤어/바디), 1 product (선크림) with
  category mappings. Integration tests must account for this seed data.

**Last updated:** 2026-05-11 by OLV-022.

---

## Admin API (OLV-022)

**Endpoints**: `/api/admin/products` (requires `PRODUCT_ADMIN` role)
- `POST /` — create product with options + categories + images (atomic)
- `PATCH /{id}` — partial update (name, description, prices, status)
- `GET /` — paginated list with filters (status/brand_id/category_id/name LIKE)
- `POST /{id}/options` — add new option
- `PATCH /{id}/options/{optionId}` — update option

**Image Upload**: `POST /api/admin/uploads/product-image` returns presigned S3 URL (`{uploadUrl, fileUrl}`). Caller PUTs bytes directly to S3.

**State Transition Validation** (Entity responsibility):
```java
// DRAFT → ON_SALE; ON_SALE ↔ SOLD_OUT; ON_SALE → STOPPED; HIDDEN anytime
public boolean isValidTransition(ProductStatus newStatus) {
    return switch (this.status) {
        case DRAFT -> newStatus == ON_SALE || newStatus == HIDDEN;
        case ON_SALE -> newStatus == SOLD_OUT || newStatus == STOPPED || newStatus == HIDDEN;
        case SOLD_OUT -> newStatus == ON_SALE || newStatus == HIDDEN;
        case STOPPED -> newStatus == HIDDEN; // one-way street
        case HIDDEN -> false; // terminal state for transitions
    };
}
```

**Hibernate Performance**:
- `MultipleBagFetchException` 해결: 두 `@OneToMany` 컬렉션(images, options)을 동시에 fetch 불가 → `@BatchSize(size = 10)`로 지연 로딩 최적화
- `ProductRepository.findByIdWithDetails()`는 brand JOIN FETCH, images JOIN FETCH — options는 @BatchSize로 N+1 방지

**Audit Logging**: 모든 admin mutation은 `ApplicationEventPublisher.publishEvent()`로 `AdminMutationEvent` 발행 → before/after JSON 스냅샷 포함
