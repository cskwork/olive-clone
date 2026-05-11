# OLV-101 Explore 세부 노트

## 출발선 확인 (OLV-100 산출물)

검색 인프라는 이미 다음과 같이 깔려 있다:

- `products` 인덱스 자동 생성: `SearchIndexInitializer` (분석기=standard, 9 필드 - PRD §13.2 그대로).
  - `src/main/java/com/olive/commerce/search/SearchIndexInitializer.java:62-83`
- 동기 파이프라인: admin 변경 → outbox enqueue → `OutboxIndexerWorker` 1초 폴링 → `ProductIndexer.indexBulk` → OS bulk.
- `OpenSearchClient` 빈은 `common/config/OpenSearchConfig.java`에 connect=2s, socket=3s 타임아웃 박혀 있음 — hang 안 됨.
- 도커: `opensearchproject/opensearch:2.15.0` (security off, single node).

본 티켓이 새로 만들 것: OpenSearch에 **읽기** 트래픽을 보내는 검색 도메인 패키지 + 3개 사용자 대면 엔드포인트.

## 응답 모양 정렬

`/api/products`(OLV-023, `ProductPublicService`/`ProductPublicController`)와 envelope을 정확히 맞춰야 함:

- 봉투: `ApiResponse<List<ProductDtos.PublicListItem>>` + `PageMeta(page, size, total)`.
- `PublicListItem` 필드: productId, brandName, productName, salePrice, originalPrice, discountRate, thumbnailUrl, rating, reviewCount.
- 인덱스 문서에는 thumbnailUrl/originalPrice/discountRate가 없음 → OpenSearch에서 hit IDs만 뽑고, productId 묶음으로 DB hydration(단일 batch query)을 한 뒤 PublicListItem 변환. 이렇게 하면 `/api/products` 응답 코드와 동일한 출력 코드(혹은 helper)를 재사용 가능.

## OpenSearch 질의 설계

### `/api/search/products`

```
POST /products/_search
{
  "from": page*size, "size": size,
  "_source": ["productId"],
  "query": {
    "bool": {
      "must":   [ { "multi_match": { "query": "선크림", "fields": ["productName^2", "tags^1.5", "brandName"] } } ],
      "filter": [
        { "term": { "status": "ON_SALE" } },
        { "term": { "categoryNames": "<category name from DB lookup>" } }?,
        { "term": { "brandName":     "<brand name from DB lookup>" } }?
      ]
    }
  },
  "sort": [ { "_score": "desc" } | { "salesCount": "desc" } | ... ]
}
```

- `keyword`가 비어 있으면 `must`는 `match_all`. 카테고리/브랜드 단독 필터 호출도 동작.
- 카테고리/브랜드 필터는 인덱스에 name 으로만 있어 ID→name lookup이 필요. ProductRepository/CategoryRepository에서 단건 lookup 후 term filter.
- Sort 옵션 매핑:
  - `relevance` (기본) → `_score desc`
  - `popular` → `salesCount desc`
  - `latest` → 인덱스에 createdAt 필드 없음 → `productId desc` (ProductPublicService와 동일 단순화, follow-up: 매핑에 createdAt 추가).
  - `price_asc/desc` → `salePrice asc/desc`
  - `rating` → `rating desc`

### `/api/search/autocomplete`

```
{
  "size": size,
  "_source": ["productName", "brandName", "tags"],
  "query": {
    "bool": {
      "should": [
        { "match_phrase_prefix": { "productName": { "query": "<prefix>" } } },
        { "prefix":              { "brandName":   "<prefix-lower>" } },
        { "prefix":              { "tags":        "<prefix-lower>" } }
      ],
      "minimum_should_match": 1,
      "filter": [ { "term": { "status": "ON_SALE" } } ]
    }
  }
}
```

- `productName`은 text + standard analyzer이므로 `match_phrase_prefix`가 자연 case-insensitive (standard analyzer lowercases). brandName/tags는 keyword라 prefix를 lowercase 입력. 한국어는 case 영향 없음.
- 응답: hit별 (productName, brandName, prefix 매칭되는 tag) 중 prefix 매치되는 string을 distinct 추출 → top size개.
- 캐시: `cache:search:autocomplete:{prefix.toLowerCase()}` TTL 5분.

### `/api/search/popular`

- 검색 시 `SearchPopularityRecorder.record(keyword)` → 키 `search:popular:bucket:{epochMinute}` ZSET에 ZINCRBY keyword 1, EXPIRE 65분.
- `@Scheduled(fixedDelay=60s)` `SearchPopularityAggregator.aggregate()` → 직전 60분 bucket key 60개를 `ZUNIONSTORE search:popular:current 60 bucket_0 ... bucket_59 AGGREGATE SUM` → EXPIRE 90초(다음 aggregator tick에서 덮어쓰니 안전).
- `GET /api/search/popular?size=10` → `ZREVRANGE search:popular:current 0 size-1 WITHSCORES`.
- 빈 keyword(공백/whitespace)는 누적/조회에서 제외.

## 503 정책

- 새 ErrorCode: `SEARCH_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE)`.
- 검색 path에서 `OpenSearchException`/`IOException`/`SocketTimeoutException` 발생 시 `BusinessException(SEARCH_UNAVAILABLE, "검색 일시 중단")` throw → `GlobalExceptionHandler`가 자동으로 envelope.
- AC3 검증: `{"success":false,"error":{"code":"SEARCH_UNAVAILABLE","message":"검색 일시 중단",...}}` + HTTP 503.
- Autocomplete/Popular는 별도 정책 — Redis 캐시만 닿으면 동작(Popular). Autocomplete는 OS 의존이라 동일하게 503으로 처리.

## 인덱스 가시성 주의

- 본 티켓의 IT는 `worker.drainOnce()` 직접 호출 후 `openSearchClient.indices().refresh(...)`로 인덱스 가시성 보장(ProductSearchSyncIT 패턴 그대로 재사용).
- `@Scheduled`는 `SchedulingConfig @Profile("!test")` 제외 — IT는 1초 폴링과 무관, 결정론적.

## 첫 실패 테스트 (TDD RED)

```java
@SpringBootTest @AutoConfigureMockMvc @Testcontainers
class SearchApiIT extends PostgresIntegrationSupport {
    @Container static OpensearchContainer<?> OPENSEARCH = ...;
    @Container static GenericContainer<?> REDIS = ...;

    @Test
    void searchByKeyword_선크림_returnsOnSaleProducts() throws Exception {
        // given: V3 seed 선크림이 인덱싱되어 있음 (enqueue + drainOnce + refresh)
        ...
        mockMvc.perform(get("/api/search/products?keyword=선크림"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data[0].productName", containsString("선크림")))
            .andExpect(jsonPath("$.meta.total").value(greaterThanOrEqualTo(1)));
    }
}
```

위 테스트가 RED여야 한다 — 컨트롤러/서비스가 아직 없으므로 404가 떨어진다.

## 위험 / 향후 follow-up

- `latest` 정렬은 인덱스의 createdAt 부재로 productId desc로 fallback. 매핑 확장 + 재색인은 후속.
- 카테고리/브랜드 이름이 변경되면 short-window 동안 stale 결과 가능. outbox가 reindex하므로 결국 일관됨.
- 한국어 분석기는 standard이므로 형태소 분석 미흡. nori 도입은 후속(이미 wiki 95 follow-up 기록됨).
- 본 티켓은 검색 클릭/노출 로그(PRD §16) 미구현 — search analytics는 별도 ticket.
