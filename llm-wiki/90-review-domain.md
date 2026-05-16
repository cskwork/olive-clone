# Review Domain

**Summary:** Customer ratings and text/image feedback for purchased items
(PRD §6.10).

**Invariants & Constraints:**

- Tables: `reviews`, `review_images`, `review_reports`, `product_review_summaries`.
- **Eligibility** (PRD §6.10): only members who own an `order_items` row
  for the product with `orders.status = 'DELIVERED'` can write a review.
  Enforced by `ReviewRepository.isEligibleForReview()` native query.
- One review per `order_item_id` (UNIQUE constraint) to prevent duplicate reviews.
- Aggregate fields (`avg_rating`, `review_count`) live in `product_review_summaries`
  table updated by `ReviewAggregateSubscriber` listening to `ReviewCreatedEvent`.
- `review_reports` allows users to flag inappropriate reviews; admin
  hide-marks them (`status = HIDDEN`) rather than deleting (preserves audit trail).

**Files of interest:**

- `src/main/java/com/olive/commerce/review/Review.java` - Review entity
- `src/main/java/com/olive/commerce/review/ReviewService.java` - Business logic
- `src/main/java/com/olive/commerce/review/ReviewController.java` - User API
- `src/main/java/com/olive/commerce/review/ReviewAdminController.java` - Admin API
- `src/main/java/com/olive/commerce/review/ReviewAggregateSubscriber.java` - Aggregate updater
- `src/main/resources/db/migration/V11__review.sql` - Schema

**Decision log:**

- 2026-05-13 | OLV-140 | E2E 테스트에서 `ReviewRepository.isEligibleForReview()`가
  `orders.status = 'DELIVERED'`를 확인함을 검증. 배송 완료 시 `deliveries.status`만
  변경하면 리뷰 자격이 부여되지 않으므로, `orders.status`도 명시적으로 `DELIVERED`로
  변경해야 함 (PRD §6.10).
- 2026-05-13 | OLV-090 | Implemented review domain with Spring Event-based aggregate
  maintenance. Fixed test data FK consistency issue (order_items.product_option_id).
  ProductPublicApiIT failure identified as pre-existing AWS S3 region config issue.
- 2026-05-10 | seed | Aggregate reviews into `product_review_summaries`
  table updated by `ReviewCreatedEvent` subscriber.

**Last updated:** 2026-05-13 by OLV-140.
