# Order Domain

**Summary:** The bookkeeping for confirmed purchase intent. Orders are the
single integration point between member, product, inventory, promotion, and
payment domains (PRD §6.5, §7.5-7.6, §8.3, §9.1, §9.2).

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

- **Order cancel flow** (PRD §9.2, OLV-062):
  - User cancel: `PAYMENT_PENDING/PAID/PREPARING → CANCELED` only
    - `SHIPPING/DELIVERED` returns 422 (use return flow)
  - Admin force cancel: any non-terminal state → CANCELED
  - Cancel side effects (atomic transaction):
    1. PG payment cancel (if PAID/PREPARING) — OLV-072
    2. Inventory reservation release (status = RELEASED)
    3. Coupon restore (member_coupon.status = ISSUED)
    4. Point restore (point_history.change_type = CANCEL)
    5. Order status → CANCELED
    6. Order status history entry
    7. Outbox event ORDER_CANCELED
    8. Spring ApplicationEvent OrderCanceledEvent
  - Idempotency: already-canceled orders are no-op

- **Order list/detail APIs** (OLV-063):
  - User endpoints: `GET /api/me/orders`, `GET /api/me/orders/{orderNo}`
    - Pagination support (page, size, status filter)
    - Ownership validation (order.memberId == principal.id)
  - Admin endpoints: `GET /api/admin/orders`, `GET /api/admin/orders/{orderId}`, `PATCH /api/admin/orders/{orderId}/status`
    - Multi-filter support (status, memberId, date range)
    - PII masking in list view (name, phone, address)
    - Full detail in single order view (no PII masking)
    - Status transition validation (admin-only transitions)
    - Audit log on status change

**Files of interest:**

- `OrderService.java` — `createOrder()`, `cancelUserOrder()`, `cancelAdminOrder()`, `getMyOrders()`, `getMyOrderDetail()`, `getAdminOrders()`, `getAdminOrderDetail()`, `updateOrderStatus()`
- `OrderController.java` — `POST /api/orders` (create), `POST /api/orders/{orderNo}/cancel`, `GET /api/me/orders`, `GET /api/me/orders/{orderNo}`
- `OrderAdminController.java` — `POST /api/admin/orders/{orderId}/cancel`, `GET /api/admin/orders`, `GET /api/admin/orders/{orderId}`, `PATCH /api/admin/orders/{orderId}/status`
- `Order.java` — state machine, `toCanceled()`, `forceCanceled()`, `toPaid()`, `toPreparing()`, `toShipping()`, `toDelivered()`
- `OrderCancelApiIT.java` — integration tests (user + admin cancel)
- `PIIMasker.java` — PII masking utility (name, phone, address)

**Decision log:**

- 2026-05-10 | seed | `order_no` format `ORD<yyyyMMdd><6-digit-seq>`, seq
  resets daily via DB sequence.
- 2026-05-12 | OLV-062 | Cancel idempotency: check CANCELED status before validation
  to allow replay without errors. Admin cancel uses `forceCanceled()` bypassing
  state machine validation; user cancel respects state machine.
- 2026-05-12 | OLV-063 | Order list/detail APIs implemented with pagination and PII masking.
  Admin status transitions validated via `isValidAdminTransition()`.

**Last updated:** 2026-05-12 by OLV-063
