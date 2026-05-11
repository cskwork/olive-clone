# OLV-021 Brand & Category Admin API - Explore Details

## 인바리언트 확인 (llm-wiki + PRD)

### Product Domain (llm-wiki/20-product-domain.md)
- **Tables**: `brands`, `categories` (self-referencing FK on parent_id)
- **Brand**: id, name UNIQUE, slug UNIQUE, logo_url, status (ACTIVE|INACTIVE)
- **Category**: id, parent_id NULLABLE FK self, name, slug, sort_order, depth
- **Money 필드 없음**: brands/categories는 가격 정보 없음
- **Status enum**: brands만 status 있음 (categories 없음)

### Common Conventions (llm-wiki/01-common-conventions.md)
- **ApiResponse<T>** envelope: success=true → data+meta, success=false → error
- **ErrorCode enum**: 새 코드 추가 시 enum에만 추가 → 핸들러 자동 매핑
- **401/403**: SecurityFilterChain의 EntryPoint/AccessDeniedHandler가 책임
- **Pagination**: PageMeta(page, size, total)

### Security (llm-wiki/98-security.md)
- **Role hierarchy**: SUPER_ADMIN ⊇ {PRODUCT_ADMIN, ORDER_ADMIN, CS_MANAGER} ⊇ USER
- **@PreAuthorize("hasRole('PRODUCT_ADMIN')")** — Spring 6은 URL 매처와 method 보안 양쪽에 자동 적용
- **admin endpoint**: `/api/admin/**` → 4가지 admin 역할만 통과

### Infra Baseline (llm-wiki/03-infra-baseline.md)
- **Redis 빈**: `StringRedisTemplate` (Spring 자동설정, 우리 코드는 안 정의해도 됨)
- **Cache key 패턴**: `cache:categories:tree`
- **TTL**: 10분 (600초)

## 기존 자산 분석

### V3__product.sql (이미 존재)
```sql
-- brands 테이블 구조
CREATE TABLE brands (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(100) NOT NULL UNIQUE,
    slug        VARCHAR(100) NOT NULL UNIQUE,
    logo_url    VARCHAR(512),
    status      VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    ...
);

-- categories 테이블 구조
CREATE TABLE categories (
    id          BIGSERIAL PRIMARY KEY,
    parent_id   BIGINT       REFERENCES categories(id) ON DELETE SET NULL,
    name        VARCHAR(100) NOT NULL,
    slug        VARCHAR(100) NOT NULL,
    sort_order  INTEGER      NOT NULL DEFAULT 0,
    depth       INTEGER      NOT NULL DEFAULT 0,
    ...
);

-- Unique index: top-level category names
CREATE UNIQUE INDEX uniq_categories_name_per_parent
    ON categories (name, parent_id)
    WHERE parent_id IS NULL;
```

### 컨트롤러 패턴 (AuthController 참조)
- 생성: `ResponseEntity.status(201).body(ApiResponse.success(data))`
- 조회: `return ApiResponse.success(data)`
- DTO는 내부 클래스로 (`AuthDtos.LoginRequest` 등)

### Placeholder Admin 컨트롤러 (AdminProductPlaceholderController)
```java
@RestController
@RequestMapping("/api/admin/products")
public class AdminProductPlaceholderController {
    @PostMapping
    @PreAuthorize("hasRole('PRODUCT_ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> create(
            @AuthenticationPrincipal AuthenticatedUser principal) {
        ...
    }
}
```

## 위험 분석

### R1: Slug uniqueness → 409 처리
- brands.name UNIQUE, brands.slug UNIQUE 제약
- categories.slug는 unique 제약 없음 → category slug 중복 가능
- Integration test로 409 검증 필요

### R2: Category tree 순환 참조
- parent_id FK는 self-referencing
- 애플리케이션 레벨에서 순환 방지 필요 (parent_id = id 금지)

### R3: Cache invalidation 타이밍
- Category 수정 시 `cache:categories:tree` 삭제
- DELETE 전인지 후인지? → DB 커밋 후 삭제해야 안전

### R4: 3-level hierarchy 계산
- depth 필드가 이미 있음
- children 조회는 재귀 쿼리 또는 N+1 문제 가능성
- PostgreSQL recursive CTE 사용 권장

## 디렉토리 구조 계획

```
src/main/java/com/olive/commerce/
├── product/
│   ├── Brand.java                    (Entity)
│   ├── BrandRepository.java          (JpaRepository)
│   ├── Category.java                 (Entity)
│   ├── CategoryRepository.java       (JpaRepository + recursive query)
│   ├── BrandAdminService.java        (Service)
│   ├── CategoryAdminService.java     (Service + cache invalidation)
│   └── CategoryPublicService.java    (Service - cache read)
├── admin/
│   ├── BrandAdminController.java     (@RestController, /api/admin/brands)
│   └── CategoryAdminController.java  (@RestController, /api/admin/categories)
├── public/
│   ├── BrandPublicController.java    (@RestController, /api/brands)
│   └── CategoryPublicController.java (@RestController, /api/categories)
└── common/error/
    └── ErrorCode.java                 (BRAND_SLUG_DUPLICATE, CATEGORY_HAS_PRODUCTS 추가)
```

## Redis 캐시 전략

```java
// Cache key
private static final String CACHE_KEY = "cache:categories:tree";

// Read (public endpoint)
String cached = redisTemplate.opsForValue().get(CACHE_KEY);
if (cached != null) {
    return objectMapper.readValue(cached, CategoryTree.class);
}
// ... DB 조회 후 캐싱
redisTemplate.opsForValue().set(CACHE_KEY, json, 10, TimeUnit.MINUTES);

// Invalidate (admin POST/PATCH/DELETE)
redisTemplate.delete(CACHE_KEY);
```

## Category Tree 쿼리 (PostgreSQL Recursive CTE)

```sql
WITH RECURSIVE category_tree AS (
    -- Root nodes
    SELECT id, name, slug, parent_id, sort_order, depth, 1 AS level
    FROM categories
    WHERE parent_id IS NULL
    ORDER BY sort_order

    UNION ALL

    -- Children
    SELECT c.id, c.name, c.slug, c.parent_id, c.sort_order, c.depth, ct.level + 1
    FROM categories c
    JOIN category_tree ct ON c.parent_id = ct.id
)
SELECT * FROM category_tree ORDER BY sort_order;
```

## 공개 API 응답 포맷

```json
{
  "success": true,
  "data": [
    {"id": 1, "name": "스킨케어", "slug": "skincare", "children": [
      {"id": 4, "name": "토너", "slug": "toner", "children": []}
    ]},
    {"id": 2, "name": "메이크업", "slug": "makeup", "children": []}
  ]
}
```
