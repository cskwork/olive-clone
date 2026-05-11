# OLV-022 Explore: Recommendation

## 선택: Option A (단일 Service + 분리 컨트롤러)

### 이유

1. **책임 분리**: `ProductAdminService`는 DB 로직, `ProductUploadService`는 S3 presigned URL 발급 — 각자의 의존성을 명확히 가짐
2. **테스트 용이성**: 상품 CRUD만 테스트할 때 S3 모킹 불필요 (`@WebMvcTest` 또는 `@DataJpaTest`로 충분)
3. **확장성**: 향후 리뷰 이미지 업로드(OLV-090) 등 다른 upload 요구사항이 들어와도 `ProductUploadService` 패턴 재사용 가능
4. **감사 로그 중앙화**: `ProductAdminService`의 `@Transactional` 메서드에 감사 로그를 통합하여 before/after 스냅샷을 한 곳에서 관리

### 첫 실패 테스트 (First Failing Test)

**테스트**: `ProductAdminApiIT.createProduct_withTwoOptionsAndTwoCategories_returns201`

```java
@WebMvcTest(controllers = ProductAdminController.class)
@ImportAutoConfiguration({SecurityAutoConfiguration.class})
@Import({GlobalExceptionHandler.class, RequestIdFilter.class})
class ProductAdminApiIT {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper mapper;

    @Test
    void createProduct_withTwoOptionsAndTwoCategories_returns201() throws Exception {
        // Given: 브랜드 1개, 카테고리 2개 (Flyway V3 데이터 활용)
        // Presigned URL로 이미지 2개 업로드 먼저 수행

        // When: 상품 생성 요청 (옵션 2개 + 카테고리 2개 + 이미지 2개)
        String request = """
            {
                "brandId": 1,
                "name": "테스트 상품",
                "description": "설명",
                "basePrice": 10000,
                "salePrice": 8000,
                "status": "DRAFT",
                "categoryIds": [1, 2],
                "options": [
                    {"optionName": "50ml", "optionPrice": 0},
                    {"optionName": "100ml", "optionPrice": 2000}
                ],
                "imageUrls": [
                    "https://s3.local/products/test-1.png",
                    "https://s3.local/products/test-2.png"
                ]
            }
            """;

        // Then: 201 Created + 응답 검증
        mockMvc.perform(post("/api/admin/products")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_PRODUCT_ADMIN")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(request))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.id").exists())
            .andExpect(jsonPath("$.data.name").value("테스트 상품"))
            .andExpect(jsonPath("$.data.options.length()").value(2))
            .andExpect(jsonPath("$.data.categories.length()").value(2))
            .andExpect(jsonPath("$.data.images.length()").value(2));
    }
}
```

**실패 원인**: `ProductAdminController`, `Product`, `ProductAdminService` 등이 아직 존재하지 않음 → 컴파일/런타임 실패

**해결 순서** (TDD):
1. RED: 위 테스트 실행 → `NoSuchBeanDefinitionException` 또는 404
2. GREEN: `Product` Entity → `ProductRepository` → `ProductAdminService` → `ProductAdminController` 순으로 구현
3. REFACTOR: 상태 천이 검증, 감사 로그 추가

---

## 구현 범위 상세

### 1. Domain Layer
- `Product.java` Entity — JPA 매핑, `ProductStatus` enum, `isValidTransition()`
- `ProductOption.java` Entity — `option_price`는 BigDecimal
- `ProductImage.java` Entity — `url`, `sort_order`, `is_thumbnail`

### 2. Repository Layer
- `ProductRepository.java` — `findAllWithFilters(status, brandId, categoryId, nameLike)`
- `ProductOptionRepository.java` — 기본 CRUD
- `ProductImageRepository.java` — 기본 CRUD

### 3. Service Layer
- `ProductAdminService.java`:
  - `create(ProductCreateRequest)` — 트랜잭션으로 products + options + categories + images 생성
  - `update(Long id, ProductUpdateRequest)` — 부분 업데이트, 상태 천이 검증
  - `get(Long id)` — 단건 조회 (options + images 포함)
  - `list(ProductListRequest)` — 페이지네이션 + 필터
  - `addOption(Long productId, OptionCreateRequest)`
  - `updateOption(Long productId, Long optionId, OptionUpdateRequest)`
- `ProductUploadService.java`:
  - `getPresignedUrl(String filename, long fileSize, String contentType)` — S3 presigned URL 생성

### 4. Controller Layer
- `ProductAdminController.java` — `/api/admin/products/*` 엔드포인트
- `ProductUploadController.java` — `/api/admin/uploads/product-image`

### 5. DTO (sealed interface)
- `ProductDts.java`:
  - `AdminCreateRequest` — brandId, name, description, basePrice, salePrice, status, categoryIds[], options[], imageUrls[]
  - `AdminUpdateRequest` — 부분 업데이트 (모든 필드 nullable)
  - `AdminResponse` — id, brand, name, description, prices, status, categories[], options[], images[]
  - `OptionCreateRequest`, `OptionUpdateRequest`
  - `PresignedUrlRequest`, `PresignedUrlResponse`

### 6. ErrorCode 확장
- `PRODUCT_NOT_FOUND` (404)
- `INVALID_PRODUCT_STATE_TRANSITION` (422)
- `INVALID_PRICE` (400) — 음수 가격
- `FILE_SIZE_EXCEEDED` (400)

### 7. 감사 로그
- `ProductAdminService`의 각 mutating 메서드에서 `auditLogger.log("ADMIN_MUTATION", ...)`
- before/after 스냅샷을 JSON으로 기록

---

## 주의사항

1. **BigDecimal 사용**: 모든 가격 필드는 `BigDecimal`로 선언, `@Column`에서 `DECIMAL(12,2)` 매핑
2. **상태 천이 검증**: `ProductStatus.isValidTransition(from, to)` 메서드로 허용 천이만 통과
3. **트랜잭션 경계**: `create()` 메서드는 `@Transactional`로 options/categories/images와 원자성 보장
4. **Presigned URL 보안**: `getPresignedUrl()`에서 파일 크기(최대 10MB)와 Content-Type(image/*) 검증
5. **Flyway 데이터 고려**: V3 마이그레이션으로 이미 데모 데이터(브랜드 1개, 카테고리 3개, 상품 1개)가 들어감
