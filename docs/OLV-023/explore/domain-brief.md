# Domain Brief - OLV-023

## 개요

공개 상품 목록/상세 API + cache-aside 패턴 구현. 이 시스템에서 가장 트래픽이 높은 읽기 엔드포인트로, 향후 category/brand/search 캐시의 패턴이 된다.

## 관련 Wiki 요약

- `20-product-domain.md`: 상품은 `products` / `product_options` / `product_images` / `brands` / `categories` / `product_category_mapping`으로 구성. 상태는 `DRAFT | ON_SALE | SOLD_OUT | STOPPED | HIDDEN`. 돈은 `BigDecimal`, 이미지는 S3 URL만 저장.
- `03-infra-baseline.md`: Redis는 `StringRedisTemplate` (Spring 자동설정)으로 접근. `@Autowired RedisTemplate<String,String>`으로 바로 주입받음.
- `01-common-conventions.md`: `ApiResponse<T>` envelope, `ErrorBody{code, message, ...}`.

## 기존 코드 패턴

1. **CategoryPublicService** (`CategoryPublicService.java:36-48`): cache-aside 패턴
   - `redisTemplate.opsForValue().get(key)` 시도
   - Cache miss 시 DB에서 build 후 `set(key, json, TTL)`
   - Deserialization 실패 시 fall-through

2. **CategoryAdminService** (`CategoryAdminService.java:96-98`): 무효화
   - `@Transactional` 메서드 마지막에 `redisTemplate.delete(CACHE_KEY)`
   - create/update/delete 직후 즉시 무효화

3. **ProductRepository** (`ProductRepository.java:30-31`): 기존 쿼리
   - `findByIdWithDetails`: brand JOIN FETCH, images JOIN FETCH (options는 @BatchSize)

## 티켓 요구사항 분석

### Endpoints
1. `GET /api/products?categoryId=&brandId=&sort=&page=&size=` — 페이지네이션 목록
   - sort ∈ `popular | latest | price_asc | price_desc | rating`
   - HIDDEN/STOPPED/DRAFT 제외 (AC3)

2. `GET /api/products/{productId}` — 상세
   - product, options (+ available_quantity from inventory), images, category path, brand, aggregate rating

### Caching
- Detail: `cache:product:detail:{productId}`, TTL 60s, `ProductUpdatedEvent`로 무효화
- List: versioned key (카운터 bump), TTL 30s

### AC (Acceptance Criteria)
1. First call populates Redis, second call <10ms (Testcontainers)
2. `PATCH /api/admin/products/{id}` invalidates detail + bumps list version (transaction-after-commit)
3. HIDDEN/STOPPED/DRAFT 제외
4. Sort by rating: `(rating, salesCount)` tiebreaker when review_count = 0

## 미구현 의존성

- **Inventory**: OLV-031 (향후 티켓). 현재는 `product_options`에 `available_quantity` 컬럼 없음.
  - AC 허용: "inventory rows missing during bootstrap window" → NULL 허용
- **Review**: `rating` / `review_count` 컬럼 없음.
  - AC4: "review_count = 0" 처리 필요

## 파일별 분석

| 파일 | 관련 내용 | 인용 |
|------|----------|------|
| `Product.java:160-187` | ProductStatus enum + 천이 검증 | `isValidTransition()` |
| `ProductDtos.java:138-171` | AdminResponse (비공개) | 공개용 DTO 새로 필요 |
| `ProductAdminService.java:174-202` | update()에서 감사 로그 | 이벤트 발행 패턴 참조 |
| `CategoryPublicService.java:36-48` | Cache-aside 패턴 | 제품용 모방 |

## 기술적 결정 포인트

1. **List cache versioning**: 단순 카운터 vs hash tag
   - 단순 카운터: `cache:product:list:v:{counter}:{hash}` 형태
   - Product write 시 `INCR cache:product:list:version`

2. **Inventory join 없음**: options만 반환, available_quantity는 NULL로

3. **Rating 정렬**: 현재 데이터 없음 → salesCount fallback만 구현

4. **Event 발행**: `@TransactionalEventListener` vs 직접 호출
   - AC2: "transaction-after-commit hook" → Spring `@TransactionalEventListener(phase=AFTER_COMMIT)`
