# Brand & Category Admin API - Feature Documentation

## Overview

브랜드와 카테고리의 Admin CRUD API와 Public Read API를 구현했습니다.

## Endpoints

### Admin Endpoints (`/api/admin/*`)

| Method | Path | Description | Role Required |
|--------|------|-------------|---------------|
| POST | `/api/admin/brands` | 브랜드 생성 | PRODUCT_ADMIN |
| GET | `/api/admin/brands` | 브랜드 목록 (페이지네이션) | PRODUCT_ADMIN |
| GET | `/api/admin/brands/{id}` | 브랜드 상세 | PRODUCT_ADMIN |
| PATCH | `/api/admin/brands/{id}` | 브랜드 수정 | PRODUCT_ADMIN |
| POST | `/api/admin/categories` | 카테고리 생성 | PRODUCT_ADMIN |
| GET | `/api/admin/categories` | 카테고리 목록 (트리) | PRODUCT_ADMIN |
| GET | `/api/admin/categories/{id}` | 카테고리 상세 | PRODUCT_ADMIN |
| PATCH | `/api/admin/categories/{id}` | 카테고리 수정 | PRODUCT_ADMIN |
| DELETE | `/api/admin/categories/{id}` | 카테고리 삭제 | PRODUCT_ADMIN |

### Public Endpoints (`/api/*`)

| Method | Path | Description | Auth |
|--------|------|-------------|------|
| GET | `/api/brands` | 브랜드 목록 (ACTIVE만, 페이지네이션) | None |
| GET | `/api/categories` | 카테고리 트리 (전체) | None |

## Request/Response Examples

### Create Brand

```bash
curl -X POST http://localhost:8080/api/admin/brands \
  -H "Authorization: Bearer <PRODUCT_ADMIN_TOKEN>" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "이니스프리",
    "slug": "innisfree",
    "logoUrl": "https://s3.local/brands/innisfree.png"
  }'
```

Response (201):
```json
{
  "success": true,
  "data": {
    "id": 1,
    "name": "이니스프리",
    "slug": "innisfree",
    "logoUrl": "https://s3.local/brands/innisfree.png",
    "status": "ACTIVE",
    "createdAt": "2026-05-11T12:00:00Z",
    "updatedAt": "2026-05-11T12:00:00Z"
  }
}
```

### Create Category

```bash
curl -X POST http://localhost:8080/api/admin/categories \
  -H "Authorization: Bearer <PRODUCT_ADMIN_TOKEN>" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "토너",
    "slug": "toner",
    "parentId": 1,
    "sortOrder": 1
  }'
```

### Public Categories Tree

```bash
curl http://localhost:8080/api/categories
```

Response:
```json
{
  "success": true,
  "data": {
    "categories": [
      {
        "id": 1,
        "name": "스킨케어",
        "slug": "skincare",
        "children": [
          {
            "id": 3,
            "name": "토너",
            "slug": "toner",
            "children": []
          },
          {
            "id": 4,
            "name": "에센스",
            "slug": "essence",
            "children": []
          }
        ]
      },
      {
        "id": 2,
        "name": "메이크업",
        "slug": "makeup",
        "children": [
          {
            "id": 5,
            "name": "쿠션",
            "slug": "cushion",
            "children": []
          }
        ]
      }
    ]
  }
}
```

## Error Codes

| Code | HTTP | Description |
|------|------|-------------|
| BRAND_SLUG_DUPLICATE | 409 | 브랜드 슬러그 중복 |
| BRAND_NOT_FOUND | 404 | 브랜드를 찾을 수 없음 |
| CATEGORY_NOT_FOUND | 404 | 카테고리를 찾을 수 없음 |
| CATEGORY_HAS_PRODUCTS | 409 | 카테고리에 매핑된 상품 존재 |
| CATEGORY_CYCLE_DETECTED | 400 | 카테고리 순환 참조 |

## Caching

Public categories endpoint는 Redis에 캐싱됩니다:

- **Cache Key**: `cache:categories:tree`
- **TTL**: 10분 (600초)
- **Invalidation**: Admin POST/PATCH/DELETE 시 즉시 무효화

## Implementation Details

- **Entity**: `Brand`, `Category` (JPA)
- **Repository**: `BrandRepository`, `CategoryRepository` (recursive CTE for tree)
- **Service**: `BrandAdminService`, `CategoryAdminService`, `CategoryPublicService`
- **Controller**: `BrandAdminController`, `CategoryAdminController`, `BrandPublicController`, `CategoryPublicController`
- **DTO**: `BrandDtos`, `CategoryDtos` (sealed interfaces)

## Files Changed

### Production Code
- `src/main/java/com/olive/commerce/product/Brand.java`
- `src/main/java/com/olive/commerce/product/Category.java`
- `src/main/java/com/olive/commerce/product/BrandRepository.java`
- `src/main/java/com/olive/commerce/product/CategoryRepository.java`
- `src/main/java/com/olive/commerce/product/BrandDtos.java`
- `src/main/java/com/olive/commerce/product/CategoryDtos.java`
- `src/main/java/com/olive/commerce/product/BrandAdminService.java`
- `src/main/java/com/olive/commerce/product/CategoryAdminService.java`
- `src/main/java/com/olive/commerce/product/CategoryPublicService.java`
- `src/main/java/com/olive/commerce/admin/BrandAdminController.java`
- `src/main/java/com/olive/commerce/admin/CategoryAdminController.java`
- `src/main/java/com/olive/commerce/public/BrandPublicController.java`
- `src/main/java/com/olive/commerce/public/CategoryPublicController.java`
- `src/main/java/com/olive/commerce/common/config/SecurityConfig.java` (public endpoints 추가)
- `src/main/java/com/olive/commerce/common/error/ErrorCode.java` (error codes 추가)

### Test Code
- `src/test/java/com/olive/commerce/admin/BrandCategoryAdminApiIT.java`
- `src/test/java/com/olive/commerce/public/BrandCategoryPublicApiIT.java`
