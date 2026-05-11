# OLV-020 Implementation Summary

## Files Created/Modified

### 1. V3__product.sql (192 lines)
**Location**: `src/main/resources/db/migration/V3__product.sql`

**Tables created** (in dependency order):
1. `brands` — brand master
2. `categories` — hierarchical categories with self-FK
3. `products` — product master with brand_id FK
4. `product_options` — options with product_id FK
5. `product_images` — images with product_id FK
6. `product_category_mapping` — M:N mapping table

**Indexes created**:
- `idx_products_status_brand` — for category-list queries
- `idx_products_name_pattern` — text_pattern_ops for LIKE searches
- `idx_product_options_product_id` — FK index
- `idx_product_images_product_sort` — composite for ordered images
- `idx_categories_parent_id` — tree navigation
- `idx_categories_depth` — tree depth filter
- `uniq_categories_name_per_parent` — partial unique for top-level

**Seed data**:
- 1 brand: 더샘 (thesecret)
- 3 categories: 스킨케어, 메이크업, 헤어/바디
- 1 product: 키즈 매일 선크림 (base: 25000, sale: 20000)
- 2 options: 50ml, 100ml
- 3 images (1 thumbnail, 2 regular)
- 3 category mappings for the demo product

### 2. ProductSchemaIntegrationTest.java (305 lines)
**Location**: `src/test/java/com/olive/commerce/product/ProductSchemaIntegrationTest.java`

**Test cases** (11 total):
1. `v3MigrationIsApplied` — checks flyway_schema_history
2. `seedBrandExists` — verifies 더샘 brand
3. `seedCategoriesExistInOrder` — verifies 3 categories
4. `seedProductWithOptionsAndImagesExists` — verifies demo product
5. `seedProductHasThreeCategoryMappings` — verifies mappings
6. `repositoryTest_InsertsProductWithTwoOptionsAndThreeCategories_ReadsBack` — AC2
7. `explainUsesTextPatternOpsIndexForLikeSearch` — AC3
8. `statusBrandIndexExists` — index verification
9. `productOptionsProductIdIndexExists` — index verification
10. `productImagesProductSortIndexExists` — index verification
11. `productCategoryMappingCompositePkExists` — PK verification
12. `categorySelfReferencingFkExists` — FK verification
13. `productsStatusEnumConstraintExists` — CHECK constraint verification

## Design Decisions

1. **brand_id NULLABLE**: Allows unbranded products (common for marketplace scenarios)
2. **sale_price NULLABLE**: NULL means "no sale", not 0.00 (different semantics)
3. **Composite PK for mapping**: Prevents duplicate mappings at DB level
4. **Partial unique for categories**: Only top-level names protected; child duplicates handled by app
5. **ON DELETE SET NULL for products.brand_id**: Product survives brand deletion
6. **ON DELETE CASCADE for options/images/mappings**: Child rows auto-deleted
