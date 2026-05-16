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
- `SearchPublicController` — 3 endpoints (`/api/search/products`, `/api/search/autocomplete`, `/api/search/popular`).
- `SearchService` — OS bool query + DB hydration (N+1 없음: `WHERE id IN (...)` 단일 batch).
- `AutocompleteService` — `match_phrase_prefix` + `prefix` bool query, Redis 5분 캐시.
- `SearchPopularityRecorder` — 분 단위 ZSET 누적 (`search:popular:bucket:{epochMinute}`).
- `SearchPopularityAggregator` — 1분 주기 ZUNIONSTORE로 직전 60분 집계, `@Profile("!test")`.

**Decision log:**

- 2026-05-10 | seed | OpenSearch (not Elasticsearch) for license clarity.
- 2026-05-10 | seed | Index sync via outbox table + scheduled drainer to
  guarantee at-least-once delivery without coupling to product writes.
- 2026-05-10 | OLV-003 | OpenSearch baseline 빈은 `OpenSearchClient` (legacy
  `RestClient` + `RestClientTransport` + `JacksonJsonpMapper`) 1개로 노출
  (`llm-wiki/03-infra-baseline.md`). 본 도메인 티켓의 인덱서 워커는 이 빈을
  outbox 드레이너에서만 호출하고, 상품 쓰기 패스에 직접 두지 않는다.
- 2026-05-11 | OLV-100 | `products` 인덱스 + outbox 동기 파이프라인 구축.
  - 매핑은 PRD §13.2/wiki 스펙 9 필드. 분석기는 **standard** (도커 기본 이미지에
    `nori` 미포함). 한국어 검색 품질 개선은 follow-up — 이미지에 nori plugin을
    추가 빌드한 뒤 매핑 변경.
  - `OpenSearchConfig`에 클라이언트 측 connect=2s, socket=3s 타임아웃 추가.
    pause로 hang 시 워커 스레드가 영원히 막히는 운영 위험을 IT(`ProductSearchSyncIT
    .openSearchDown_*`)가 잡아냄. 이 timeout이 없으면 fixedDelay 폴링이 의미 없음.
  - `@EnableScheduling`은 `SchedulingConfig` (`@Profile("!test")`)로 분리. JVM-싱글톤
    Postgres + 다중 SpringBootTest 컨텍스트 캐시 조합에서 다른 컨텍스트의 워커가
    PENDING row를 가로채 OLV-100 IT가 비결정적으로 실패하던 문제 해결. IT는
    `worker.drainOnce()`를 수동 호출하여 결정론적 검증.
  - `tags`/`rating`/`salesCount`/`reviewCount`는 소스 도메인 미구현으로 빈 리스트/
    0 디폴트. 도메인이 생기는 ticket에서 활성화.
  - DLQ 임계값 5회. DLQ 해제 어드민은 follow-up. 단일 productId 수동 재인덱스는
    `POST /api/admin/search/reindex/{productId}` (outbox 경유, 202 Accepted).
  - 전체 재색인: `./gradlew reindexProducts` — `reindex` 프로필로 부팅 후
    100건 페이지로 bulk index + `SpringApplication.exit`.
- 2026-05-13 | OLV-101 | **Read-path 구현** — Search API 3종 + 인기검색어 집계.
  - Plan A 채택: 매핑 변경 없이 기존 9필드 그대로 질의. categoryId/brandId는 DB
    lookup으로 name 변환 후 term filter. hydration은 size≤100 단일 batch라 N+1 위험 없음.
  - `/api/search/products`: `_score`/salesCount/createdAt desc/salePrice/rating 정렬,
    status=ON_SALE 강제 filter. OS 다운 시 503 `SEARCH_UNAVAILABLE`(메시지 "검색 일시 중단").
  - `/api/search/autocomplete`: `should` bool (match_phrase_prefix on productName,
    prefix on brandName/tags). Redis 5분 캐시 (`cache:search:autocomplete:{prefix-lower}:{size}`).
  - `/api/search/popular`: `SearchPopularityRecorder`가 분-bucket ZSET에 ZINCRBY 누적
    (TTL 65분). `SearchPopularityAggregator`가 1분 주기 ZUNIONSTORE로 직전 60분 합산
    → `search:popular:current` ZSET (TTL 2분). Redis 실패 시 검색 결과는 정상, 인기검색어만
    디그레이드 (빈 리스트 반환).
  - 테스트: SearchApiIT (AC1/AC2/AC3), AutocompleteApiIT (AC4), PopularKeywordsApiIT (AC5).

**Last updated:** 2026-05-13 by OLV-101.
