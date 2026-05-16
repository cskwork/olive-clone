# OLV-101: Search API + Autocomplete + Popular Keywords

## 개요

사용자 대면 검색 API 3종을 OpenSearch + Redis 위에 구현합니다.

- `GET /api/search/products` — 키워드/카테고리/브랜드 필터 + 6종 정렬
- `GET /api/search/autocomplete` — prefix 자동완성 (Redis 5분 캐시)
- `GET /api/search/popular` — 직전 1시간 인기검색어 TOP-N

## 구현 상세

### 1. SearchPublicController

`/api/search/*` 3개 엔드포인트를 노출하는 공개 컨트롤러입니다.

- `products()`: keyword, categoryId, brandId, sort, page, size 파라미터
  - sort: RELEVANCE(기본), POPULAR, LATEST, PRICE_ASC, PRICE_DESC, RATING
  - size clamp: 1~100 (기본 20)
- `autocomplete()`: prefix, size 파라미터 (최대 20)
- `popular()`: size 파라미터 (최대 100)

### 2. SearchService

OpenSearch 키워드 검색을 수행합니다.

**Query 구조**:
- `must`: multi_match (productName^2, tags^1.5, brandName)
- `filter`: status=ON_SALE + categoryNames term + brandName term

**Hydration**: hit ID 목록 → DB 조회 (`ProductRepository.findAllById`) → `PublicListItem` 변환
- thumbnailUrl: `ProductImageRepository.findThumbnailUrlsByProductIds`로 batch 조회
- categoryId/brandId lookup: name 변환 후 term filter

**정렬 옵션**:
- RELEVANCE: `_score desc`
- POPULAR: `salesCount desc`
- LATEST: `productId desc` (인덱스에 createdAt 없음)
- PRICE_ASC/DESC: `salePrice asc/desc`
- RATING: `rating desc`

**실패 처리**: OpenSearchException/IOException → `ErrorCode.SEARCH_UNAVAILABLE`(503, "검색 일시 중단")

### 3. AutocompleteService

prefix 매칭 자동완성입니다.

**Query 구조**:
- `should`: match_phrase_prefix(productName), prefix(brandName), prefix(tags)
- `minimumShouldMatch: 1`
- `filter`: status=ON_SALE

**캐시**: Redis 5분 TTL, key=`cache:search:autocomplete:{prefix-lower}:{size}`

**case-insensitive**: productName(text analyzer)는 lowercase 정규화, brandName/tags는 lowercased prefix 전송

### 4. SearchPopularityRecorder

분 단위 ZSET에 검색 횟수 누적합니다.

- key: `search:popular:bucket:{epochMinute}`
- TTL: 65분
- 실패 시 디그레이드 (로그만 남기고 검색 결과는 정상 반환)

### 5. SearchPopularityAggregator

1분 주기로 직전 60 bucket을 합산합니다.

- `@Scheduled(fixedDelay = 60000)`
- `test` 프로필에서 비활성 (`SchedulingConfig`)
- `ZUNIONSTORE`로 `search:popular:current` ZSET 생성
- TTL: 2분

### 6. SearchDtos

검색 도메인 DTO:
- `SortOption`: 6종 정렬 enum
- `AutocompleteResponse`: suggestions 리스트
- `PopularKeyword`: keyword + count
- `PopularResponse`: keywords 리스트

## 테스트

| IT | 커버리지 AC |
|----|-----------|
| SearchApiIT | AC1(키워드 검색), AC2(카테고리 필터), AC3(OS 다운 시 503) |
| AutocompleteApiIT | AC4(prefix 매칭, case-insensitive) |
| PopularKeywordsApiIT | AC5(100회 검색 후 인기검색어 비어있지 않음) |

## 의존 모듈

- OLV-100 (ProductIndexEnqueuer, OutboxIndexerWorker, ProductDocument) — 완료
- common/api (ApiResponse, PageMeta)
- common/error (ErrorCode.SEARCH_UNAVAILABLE)
- product (ProductPublicService 응답 모양 재사용)
