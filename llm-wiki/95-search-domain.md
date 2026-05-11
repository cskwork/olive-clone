# Search Domain

**Summary:** Keyword search, autocomplete, popular searches, ranked product
listing (PRD §6.3, §13). Starts as DB LIKE for the very first ticket, then
moves to OpenSearch as soon as catalog scales.

**Invariants & Constraints:**

- **Phase 1 (initial)**: DB LIKE search on `products.name` filtered by
  `status = 'ON_SALE'` (PRD §13.1). Acceptable up to ~10K SKUs.
- **Phase 2 (OpenSearch)**: index per-product document with the exact
  shape from PRD §13.2:

  ```json
  {
    "productId": 1,
    "productName": "수분 진정 선크림",
    "brandName": "브랜드명",
    "categoryNames": ["스킨케어", "선케어"],
    "tags": ["선크림", "수분", "민감성"],
    "salePrice": 18000,
    "rating": 4.7,
    "reviewCount": 132,
    "salesCount": 5000,
    "status": "ON_SALE"
  }
  ```

- Index sync is **event-driven via outbox** — never synchronous in the
  product write path. `ProductUpdatedEvent` → outbox → indexer worker →
  OpenSearch bulk API. Failed bulk operations land in a dead-letter table.
- Sort options exposed (PRD §4.1 검색): 정확도 / 판매량 / 인기 / 최신 /
  가격 오름차순 / 가격 내림차순.
- OpenSearch outage runbook (PRD §15.3): expose a "검색 일시 중단" message
  on the search endpoint; do NOT fall back to DB LIKE silently — it would
  return inconsistent results vs. the indexed expectation.

**Files of interest:**

- PRD §6.3, §13, §15.3.

**Decision log:**

- 2026-05-10 | seed | OpenSearch (not Elasticsearch) for license clarity.
- 2026-05-10 | seed | Index sync via outbox table + scheduled drainer to
  guarantee at-least-once delivery without coupling to product writes.
- 2026-05-10 | OLV-003 | OpenSearch baseline 빈은 `OpenSearchClient` (legacy
  `RestClient` + `RestClientTransport` + `JacksonJsonpMapper`) 1개로 노출
  (`llm-wiki/03-infra-baseline.md`). 본 도메인 티켓의 인덱서 워커는 이 빈을
  outbox 드레이너에서만 호출하고, 상품 쓰기 패스에 직접 두지 않는다.

**Last updated:** 2026-05-10 by OLV-003.
