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
- Points are **ledger-based** (PRD §6.8): source of truth is `point_histories`.
  `points.balance` is a cached rollup updated by `update_points_balance()` trigger.
  Can be recomputed via: `SUM(amount) WHERE member_id = ? AND available_at <= now() AND (expires_at IS NULL OR expires_at > now())`.
- Points earned from an order are scheduled (배송 완료 후 N일) — write a
  `point_histories` row with `available_at` in the future, batch flips it
  to spendable.

**Files of interest:**

- PRD §6.8, §17.
- `src/main/java/com/olive/commerce/promotion/{Coupon,MemberCoupon,CouponService}.java` (OLV-051)
- `src/main/java/com/olive/commerce/admin/CouponAdminController.java` (OLV-051)

**Decision log:**

- 2026-05-11 | OLV-051 | 대량 발급 동시성 제어는 `SELECT FOR UPDATE`로 구현. `CouponRepository.findByIdForUpdate()`가 JPA `@Lock(PESSIMISTIC_WRITE)`을 사용하여 `coupons` 행을 잠금. `issued_count` 체크와 증가가 원자적으로 실행됨.
- 2026-05-11 | OLV-051 | 중복 발급 방지는 `member_coupons(member_id, coupon_id)` WHERE status='ISSUED' 부분 유니크 인덱스로 DB가 강제. 애플리케이션 레벨 락 불필요.
- 2026-05-11 | OLV-051 | 쿠폰 검증 실패는 `BusinessException(ErrorCode.COUPON_INVALID, CouponInvalidReason.name())`으로 OLV-061 주문 서비스에 전달. `CouponInvalidReason` enum: `EXPIRED | NOT_OWNED | ALREADY_USED | MIN_AMOUNT_NOT_MET | NOT_APPLICABLE_PRODUCT | COUPON_INACTIVE | COUPON_NOT_FOUND`.
- 2026-05-11 | OLV-051 | 쿠폰 복구(`restore()`)는 idempotent하게 구현. 이미 `ISSUED` 상태인 경우 no-op으로 처리하여 멱등성 보장.
- 2026-05-11 | OLV-050 | `points.balance` 캐시 컬럼 포함. 트리거 `update_points_balance()`로 동기화. Source of truth는 여전히 `point_histories`.
- 2026-05-10 | seed | Points are ledger-only, no cached balance column.

**Last updated:** 2026-05-11 by OLV-051.
