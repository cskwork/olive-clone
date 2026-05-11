package com.olive.commerce.product;

import com.olive.commerce.common.persistence.PostgresIntegrationSupport;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace.NONE;

/**
 * OLV-020 Acceptance Criteria 검증:
 *  - V3 마이그레이션이 적용됐는가? (flyway_schema_history 행)
 *  - products / product_options / product_images / brands / categories / product_category_mapping
 *    한 행 INSERT → SELECT 왕복 가능?
 *  - Repository 테스트: product + 2 options + 3 category mappings 삽입 후 읽기
 *  - EXPLAIN: idx_products_name_pattern(text_pattern_ops)가 LIKE '선크림%'에 사용되는가?
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
@TestPropertySource(properties = {
    "spring.jpa.hibernate.ddl-auto=none"
})
class ProductSchemaIntegrationTest extends PostgresIntegrationSupport {

    @Autowired
    private EntityManager em;

    @Test
    void v3MigrationIsApplied() {
        Number count = (Number) em.createNativeQuery("""
                SELECT COUNT(*) FROM flyway_schema_history
                WHERE version = '3' AND success = TRUE
                """).getSingleResult();
        assertThat(count.intValue()).isEqualTo(1);
    }

    @Test
    void seedBrandExists() {
        Object[] brand = (Object[]) em.createNativeQuery("""
                SELECT name, slug, logo_url, status
                FROM brands
                WHERE slug = 'thesecret'
                """).getSingleResult();

        assertThat(brand[0]).isEqualTo("더샘");
        assertThat(brand[1]).isEqualTo("thesecret");
        assertThat(brand[2]).isEqualTo("https://s3.local/brands/thesecret.png");
        assertThat(brand[3]).isEqualTo("ACTIVE");
    }

    @Test
    void seedCategoriesExistInOrder() {
        @SuppressWarnings("unchecked")
        var rows = em.createNativeQuery("""
                SELECT name, slug, sort_order, depth
                FROM categories
                ORDER BY sort_order
                """).getResultList();

        assertThat(rows).hasSize(3);
        assertThat(((Object[]) rows.get(0))[0]).isEqualTo("스킨케어");
        assertThat(((Object[]) rows.get(1))[0]).isEqualTo("메이크업");
        assertThat(((Object[]) rows.get(2))[0]).isEqualTo("헤어/바디");
    }

    @Test
    void seedProductWithOptionsAndImagesExists() {
        Object[] product = (Object[]) em.createNativeQuery("""
                SELECT p.name, p.status, p.base_price, p.sale_price, b.name
                FROM products p
                LEFT JOIN brands b ON p.brand_id = b.id
                WHERE p.name LIKE '%선크림%'
                """).getSingleResult();

        assertThat(product[0]).isEqualTo("키즈 매일 선크림 SPF50+ PA++++");
        assertThat(product[1]).isEqualTo("ON_SALE");
        assertThat(product[2]).isEqualTo(new BigDecimal("25000.00"));
        assertThat(product[3]).isEqualTo(new BigDecimal("20000.00"));
        assertThat(product[4]).isEqualTo("더샘");

        @SuppressWarnings("unchecked")
        List<Object[]> options = em.createNativeQuery("""
                SELECT option_name, option_price, status
                FROM product_options
                WHERE product_id = (SELECT id FROM products WHERE name LIKE '%선크림%')
                ORDER BY option_price
                """).getResultList();

        assertThat(options).hasSize(2);
        assertThat(options.get(0)[0]).isEqualTo("50ml");
        // PostgreSQL returns DECIMAL as Double in native queries - compare numeric values
        assertThat(((Number) options.get(0)[1]).doubleValue()).isEqualTo(0.0);
        assertThat(options.get(1)[0]).isEqualTo("100ml");
        assertThat(((Number) options.get(1)[1]).doubleValue()).isEqualTo(5000.00);

        @SuppressWarnings("unchecked")
        List<Object[]> images = em.createNativeQuery("""
                SELECT url, sort_order, is_thumbnail
                FROM product_images
                WHERE product_id = (SELECT id FROM products WHERE name LIKE '%선크림%')
                ORDER BY sort_order
                """).getResultList();

        assertThat(images).hasSize(3);
        assertThat(images.get(0)[2]).isEqualTo(true);  // 첫 번째가 썸네일
        assertThat(images.get(1)[2]).isEqualTo(false);
    }

    @Test
    void seedProductHasThreeCategoryMappings() {
        Number count = (Number) em.createNativeQuery("""
                SELECT COUNT(*)
                FROM product_category_mapping
                WHERE product_id = (SELECT id FROM products WHERE name LIKE '%선크림%')
                """).getSingleResult();

        assertThat(count.intValue()).isEqualTo(3);
    }

    @Test
    void repositoryTest_InsertsProductWithTwoOptionsAndThreeCategories_ReadsBack() {
        // Given: demo brand + categories already seeded
        Long brandId = ((Number) em.createNativeQuery(
                "SELECT id FROM brands WHERE slug = 'thesecret'"
        ).getSingleResult()).longValue();

        // When: product + 2 options + 3 category mappings insert
        Long productId = ((Number) em.createNativeQuery("""
                INSERT INTO products (brand_id, name, description, status, base_price, sale_price)
                VALUES (:brandId, :name, :desc, 'ON_SALE', :basePrice, :salePrice)
                RETURNING id
                """)
                .setParameter("brandId", brandId)
                .setParameter("name", "테스트 상품")
                .setParameter("desc", "Repository 테스트용 상품")
                .setParameter("basePrice", new BigDecimal("15000"))
                .setParameter("salePrice", new BigDecimal("12000"))
                .getSingleResult()).longValue();

        Long option1Id = ((Number) em.createNativeQuery("""
                INSERT INTO product_options (product_id, option_name, option_price, status)
                VALUES (:productId, '옵션A', 0, 'ON_SALE')
                RETURNING id
                """).setParameter("productId", productId).getSingleResult()).longValue();

        Long option2Id = ((Number) em.createNativeQuery("""
                INSERT INTO product_options (product_id, option_name, option_price, status)
                VALUES (:productId, '옵션B', 3000, 'ON_SALE')
                RETURNING id
                """).setParameter("productId", productId).getSingleResult()).longValue();

        // Get all 3 category IDs
        @SuppressWarnings("unchecked")
        List<Long> categoryIds = em.createNativeQuery(
                "SELECT id FROM categories ORDER BY sort_order"
        ).getResultList();

        em.createNativeQuery("""
                INSERT INTO product_category_mapping (product_id, category_id)
                VALUES (:productId, :categoryId1)
                """).setParameter("productId", productId)
                .setParameter("categoryId1", categoryIds.get(0))
                .executeUpdate();

        em.createNativeQuery("""
                INSERT INTO product_category_mapping (product_id, category_id)
                VALUES (:productId, :categoryId2)
                """).setParameter("productId", productId)
                .setParameter("categoryId2", categoryIds.get(1))
                .executeUpdate();

        em.createNativeQuery("""
                INSERT INTO product_category_mapping (product_id, category_id)
                VALUES (:productId, :categoryId3)
                """).setParameter("productId", productId)
                .setParameter("categoryId3", categoryIds.get(2))
                .executeUpdate();

        em.flush();
        em.clear();

        // Then: read back and verify
        Object[] product = (Object[]) em.createNativeQuery("""
                SELECT name, base_price, sale_price, status
                FROM products WHERE id = :id
                """).setParameter("id", productId).getSingleResult();

        assertThat(product[0]).isEqualTo("테스트 상품");
        assertThat(product[1]).isEqualTo(new BigDecimal("15000.00"));
        assertThat(product[2]).isEqualTo(new BigDecimal("12000.00"));
        assertThat(product[3]).isEqualTo("ON_SALE");

        @SuppressWarnings("unchecked")
        List<Object[]> options = em.createNativeQuery("""
                SELECT option_name, option_price
                FROM product_options
                WHERE product_id = :productId
                ORDER BY option_price
                """).setParameter("productId", productId).getResultList();

        assertThat(options).hasSize(2);
        assertThat(options.get(0)[0]).isEqualTo("옵션A");
        assertThat(options.get(1)[0]).isEqualTo("옵션B");

        Number mappingCount = (Number) em.createNativeQuery("""
                SELECT COUNT(*) FROM product_category_mapping WHERE product_id = :productId
                """).setParameter("productId", productId).getSingleResult();

        assertThat(mappingCount.intValue()).isEqualTo(3);
    }

    @Test
    void explainUsesTextPatternOpsIndexForLikeSearch() {
        // AC3: EXPLAIN on LIKE '선크림%' must use idx_products_name_pattern
        // Note: status 필터를 제거하여 name 인덱스 사용을 강제
        @SuppressWarnings("unchecked")
        List<String> plan = em.createNativeQuery("""
                EXPLAIN
                SELECT * FROM products WHERE name LIKE '선크림%'
                """).getResultList();

        String planText = String.join(" ", plan);
        assertThat(planText)
                .as("idx_products_name_pattern(text_pattern_ops) 인덱스가 사용되어야 함")
                .contains("idx_products_name_pattern");
    }

    @Test
    void statusBrandIndexExists() {
        @SuppressWarnings("unchecked")
        var indexes = em.createNativeQuery("""
                SELECT indexdef FROM pg_indexes
                WHERE tablename = 'products' AND indexname = 'idx_products_status_brand'
                """).getResultList();

        assertThat(indexes).hasSize(1);
    }

    @Test
    void productOptionsProductIdIndexExists() {
        @SuppressWarnings("unchecked")
        var indexes = em.createNativeQuery("""
                SELECT indexdef FROM pg_indexes
                WHERE tablename = 'product_options' AND indexname = 'idx_product_options_product_id'
                """).getResultList();

        assertThat(indexes).hasSize(1);
    }

    @Test
    void productImagesProductSortIndexExists() {
        @SuppressWarnings("unchecked")
        var indexes = em.createNativeQuery("""
                SELECT indexdef FROM pg_indexes
                WHERE tablename = 'product_images' AND indexname = 'idx_product_images_product_sort'
                """).getResultList();

        assertThat(indexes).hasSize(1);
    }

    @Test
    void productCategoryMappingCompositePkExists() {
        @SuppressWarnings("unchecked")
        var constraints = em.createNativeQuery("""
                SELECT conname
                FROM pg_constraint
                WHERE conrelid = 'product_category_mapping'::regclass
                  AND contype = 'p'
                """).getResultList();

        assertThat(constraints).hasSize(1);
        String pkName = (String) constraints.get(0);
        assertThat(pkName).contains("product_category_mapping_pkey");
    }

    @Test
    void categorySelfReferencingFkExists() {
        @SuppressWarnings("unchecked")
        var constraints = em.createNativeQuery("""
                SELECT conname, pg_get_constraintdef(oid)
                FROM pg_constraint
                WHERE conrelid = 'categories'::regclass
                  AND contype = 'f'
                """).getResultList();

        assertThat(constraints).hasSize(1);
        Object[] constraint = (Object[]) constraints.get(0);
        String constraintDef = (String) constraint[1];
        assertThat(constraintDef).contains("REFERENCES categories(id)");
    }

    @Test
    void productsStatusEnumConstraintExists() {
        // Invalid status should be rejected by CHECK constraint
        assertThatThrownBy(() ->
                em.createNativeQuery("""
                        INSERT INTO products (brand_id, name, status, base_price)
                        VALUES ((SELECT id FROM brands LIMIT 1), 'Invalid Status Product', 'INVALID_STATUS', 10000)
                        """).executeUpdate()
        ).isInstanceOf(Exception.class);
    }
}
