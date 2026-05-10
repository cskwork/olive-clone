# Order Domain

**Summary:** The bookkeeping for confirmed purchase intent. Orders are the
single integration point between member, product, inventory, promotion, and
payment domains (PRD §6.5, §7.5-7.6, §8.3, §9.1).

**Invariants & Constraints:**

- Tables: `orders`, `order_items`, `order_price_summaries`,
  `order_status_histories`.
- Order status enum (PRD §6.5):

  ```
  CREATED            : 주문 생성
  PAYMENT_PENDING    : 결제 대기
  PAID               : 결제 완료
  PREPARING          : 상품 준비중
  SHIPPING           : 배송중
  DELIVERED          : 배송 완료
  CANCELED           : 주문 취소
  REFUND_REQUESTED   : 환불 요청
  REFUNDED           : 환불 완료
  FAILED             : 주문 실패
  ```

- **Snapshot-at-create**: `order_items` copies `product_name`, `option_name`,
  `unit_price`, and `quantity` (PRD §20.2). Future product edits MUST NOT
  retroactively change order history.
- `order_no` is human-friendly (`ORD202605100001` style) and globally
  unique. Internal joins still use the BIGINT `id`.
- Every status transition writes a row to `order_status_histories`
  (PRD §6.5) — the audit log requirement (PRD §16.2) depends on it.
- Order create pipeline (PRD §8.3) is exactly:
  1. Member 검증
  2. 상품 판매 상태 검증 (status = ON_SALE)
  3. 재고 검증 + 선점 (reserve via inventory domain)
  4. 쿠폰 사용 가능 여부 검증
  5. 포인트 사용 가능 여부 검증
  6. 최종 결제 금액 계산 (BigDecimal arithmetic; round HALF_UP)
  7. 주문 생성 + `order_items` snapshot
  8. 결제 요청 정보 생성 (handed off to payment domain)

**Files of interest:**

- PRD §6.5, §7.5-7.6, §8.3, §9.1, §20.2.

**Decision log:**

- 2026-05-10 | seed | `order_no` format `ORD<yyyyMMdd><6-digit-seq>`, seq
  resets daily via DB sequence.

**Last updated:** 2026-05-10 by seed.
