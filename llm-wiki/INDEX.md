# llm-wiki INDEX

Distilled domain knowledge for the Olive Young-style commerce backend. Every
Explore stage opens this file first and reads the entries whose topic relates
to the ticket. Learn writes back here after QA passes.

The source of truth for everything below is the PRD at the project root:
`./Oliveyoung Like Commerce Backend Design.pdf`. The wiki distills it; when
in doubt, open the PDF.

| topic-slug                  | one-line summary                                                            | Last updated         |
|-----------------------------|-----------------------------------------------------------------------------|----------------------|
| 00-architecture-overview    | Modular monolith package layout, storage map, external systems              | 2026-05-10 (OLV-001) |
| 01-common-conventions       | ApiResponse envelope / ErrorCode→HTTP / X-Request-Id MDC / audit JSON 규약    | 2026-05-10 (OLV-004) |
| 02-persistence-baseline     | Postgres+Flyway+Testcontainers baseline, BOM 1.21.4 pin, RepositoryIT 베이스 | 2026-05-10 (OLV-002) |
| 03-infra-baseline           | Redis(자동설정)/S3 LocalStack(localMode 가드)/OpenSearch(legacy transport) baseline | 2026-05-10 (OLV-003) |
| 10-member-domain            | members / addresses / grades, JWT access+refresh, role hierarchy, signup/login/refresh/logout | 2026-05-10 (OLV-011) |
| 20-product-domain           | products / options / images / brands / categories, status state machine     | 2026-05-10 (seed)    |
| 30-inventory-domain         | Per-option inventory, reserve→commit, Redis distributed lock + DB fallback  | 2026-05-10 (seed)    |
| 40-cart-domain              | carts/cart_items, anon vs member cart merge, re-validate at order time      | 2026-05-10 (seed)    |
| 50-promotion-domain         | coupons/points lifecycle, discount types (FIXED/PERCENT/BOGO/etc.)          | 2026-05-10 (seed)    |
| 60-order-domain             | Order state machine, copied-product snapshot, status history table          | 2026-05-10 (seed)    |
| 70-payment-domain           | PG idempotency, webhook callback authority, READY→APPROVED→REFUNDED         | 2026-05-10 (seed)    |
| 80-delivery-domain          | deliveries 1:N to order, async carrier API, retry queue                     | 2026-05-10 (seed)    |
| 90-review-domain            | Only purchased order_items can review; aggregate to product summary         | 2026-05-10 (seed)    |
| 95-search-domain            | DB LIKE → OpenSearch; index sync via outbox events                          | 2026-05-10 (seed)    |
| 96-eventing                 | OrderCreated / PaymentApproved / etc. + outbox pattern                      | 2026-05-10 (seed)    |
| 97-batch-jobs               | Payment-pending expiry / inventory release / coupon expiry / sales rollup   | 2026-05-10 (seed)    |
| 98-security                 | JWT, role-based access, password hashing (bcrypt 12), PII masking, payment data policy | 2026-05-10 (OLV-011) |
| 99-failure-handling         | PG outage / carrier outage / OpenSearch outage / Redis outage runbooks      | 2026-05-10 (seed)    |
