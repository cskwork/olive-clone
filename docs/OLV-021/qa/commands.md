# OLV-021 QA Commands

Java 21 미설치로 테스트 실행이 보류됨. 환경 준비 후 아래 명령을 실행하여 AC 4건을 검증하십시오.

## 선행 조건

1. Java 21 설치: `sdk install java 21.0.1-tem` 또는 `brew install openjdk@21`
2. Docker daemon 실행: Redis Testcontainers 필요
3. PostgreSQL Testcontainers 자동 실행

## Admin API 테스트

```bash
# 전체 Admin API 테스트
./gradlew test --tests BrandCategoryAdminApiIT

# AC1: 권한 분리 (403 검증)
./gradlew test --tests BrandCategoryAdminApiIT#brandsEndpoint_withUserToken_returns403
./gradlew test --tests BrandCategoryAdminApiIT#brandsEndpoint_withProductAdminToken_returns200

# AC2: 3-level 계층 구조
./gradlew test --tests BrandCategoryAdminApiIT#categoryTree_returnsNestedChildrenFor3LevelHierarchy

# AC4: Slug 중복 409
./gradlew test --tests BrandCategoryAdminApiIT#createBrand_withDuplicateSlug_returns409
```

## Public API + Cache 테스트

```bash
# 전체 Public API 테스트
./gradlew test --tests BrandCategoryPublicApiIT

# AC3: 캐시 무효화
./gradlew test --tests BrandCategoryPublicApiIT#categoryCacheInvalidated_afterUpdate
./gradlew test --tests BrandCategoryPublicApiIT#categoryCacheInvalidated_afterCreate
./gradlew test --tests BrandCategoryPublicApiIT#categoryCacheInvalidated_afterDelete
```

## 전체 테스트 실행

```bash
./gradlew test --tests "*BrandCategory*IT"
```

## 예상 결과

- 19 tests passed (Admin 14 + Public 5)
- BUILD SUCCESSFUL
- 모든 AC 항목 PASS

## curl로 수동 검증 (선택)

```bash
# 1. Public brands endpoint (no auth required)
curl http://localhost:8080/api/brands

# 2. Public categories tree endpoint (no auth required)
curl http://localhost:8080/api/categories

# 3. Admin brands endpoint (requires PRODUCT_ADMIN token)
curl -H "Authorization: Bearer <PRODUCT_ADMIN_TOKEN>" \
  http://localhost:8080/api/admin/brands

# 4. Admin categories tree endpoint
curl -H "Authorization: Bearer <PRODUCT_ADMIN_TOKEN>" \
  http://localhost:8080/api/admin/categories

# 5. Create brand (should return 201)
curl -X POST \
  -H "Authorization: Bearer <PRODUCT_ADMIN_TOKEN>" \
  -H "Content-Type: application/json" \
  -d '{"name":"Test Brand","slug":"test-brand","logoUrl":"logo.png"}' \
  http://localhost:8080/api/admin/brands
```
