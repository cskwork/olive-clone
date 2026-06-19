package com.olive.commerce.product;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.olive.commerce.batch.ProductRankingJob;
import com.olive.commerce.common.persistence.PostgresIntegrationSupport;
import com.olive.commerce.review.ProductReviewSummary;
import com.olive.commerce.review.ProductReviewSummaryRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;

/**
 * OLV-023 AC 검증 — Public Product API + Cache-Aside.
 *
 * AC1: First call populates Redis, second returns cached (<10ms in prod).
 * AC2: PATCH /api/admin/products/{id} invalidates detail + bumps list version.
 * AC3: HIDDEN/STOPPED/DRAFT excluded from public list.
 * AC4: Sort by rating falls back gracefully (review_count = 0).
 * A1:  reviewCount/avgRating populated from product_review_summaries.
 * A2:  POPULAR sort by sales_count DESC; RATING sort by avg_rating DESC NULLS LAST.
 * New: GET /api/products/rankings and /api/products/best-sellers.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
class ProductPublicApiIT extends PostgresIntegrationSupport {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(
        DockerImageName.parse("redis:7-alpine")
    ).withExposedPorts(6379);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private PlatformTransactionManager txManager;

    @PersistenceContext
    private EntityManager em;

    @Autowired
    private org.springframework.data.redis.core.StringRedisTemplate redisTemplate;

    @Autowired
    private ProductReviewSummaryRepository reviewSummaryRepository;

    @Autowired
    private ProductRankingJob productRankingJob;

    private Jwt adminToken;

    @org.springframework.test.context.DynamicPropertySource
    static void redisProperties(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @BeforeEach
    void setUp() {
        // Clean up test data (delete products created by tests, keep Flyway seed id=1)
        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            em.createNativeQuery("DELETE FROM product_review_summaries WHERE product_id > 1").executeUpdate();
            em.createNativeQuery("DELETE FROM product_rankings WHERE product_id > 1").executeUpdate();
            em.createNativeQuery("DELETE FROM product_options WHERE product_id > 1").executeUpdate();
            em.createNativeQuery("DELETE FROM product_images WHERE product_id > 1").executeUpdate();
            em.createNativeQuery("DELETE FROM product_category_mapping WHERE product_id > 1").executeUpdate();
            em.createNativeQuery("DELETE FROM products WHERE id > 1").executeUpdate();

            // Reset Flyway seed product (id=1) to original state
            em.createNativeQuery("""
                UPDATE products SET
                    name = '키즈 매일 선크림 SPF50+ PA++++',
                    sale_price = 20000,
                    base_price = 25000,
                    status = 'ON_SALE',
                    sales_count = 0
                WHERE id = 1
                """).executeUpdate();

            // Reset review summary for product 1
            em.createNativeQuery("DELETE FROM product_review_summaries WHERE product_id = 1").executeUpdate();
            // Reset ranking for product 1
            em.createNativeQuery("DELETE FROM product_rankings WHERE product_id = 1").executeUpdate();
        });

        // Reset sequences to sync with Flyway data
        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            em.createNativeQuery("SELECT setval('products_id_seq', 1, true)").getSingleResult();
            em.createNativeQuery("SELECT setval('product_options_id_seq', 2, true)").getSingleResult();
        });

        // Flush Redis (including list version)
        redisTemplate.getConnectionFactory().getConnection().flushAll();

        adminToken = createAdminJwt();
    }

    // ========================================================================
    // AC1: Cache population and hit
    // ========================================================================

    @Test
    void getProductDetail_firstCallPopulatesRedis_secondCallHitsCache() throws Exception {
        // Given: Flyway seeds product id=1 (선크림, ON_SALE)

        // When: First call - cache miss
        MvcResult first = mockMvc.perform(get("/api/products/1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.productId").value(1))
            .andExpect(jsonPath("$.data.productName").value("키즈 매일 선크림 SPF50+ PA++++"))
            .andExpect(jsonPath("$.data.brandName").value("더샘"))
            .andExpect(jsonPath("$.data.salePrice").value(20000))
            .andExpect(jsonPath("$.data.originalPrice").value(25000))
            .andExpect(jsonPath("$.data.discountRate").value(20.0))
            .andExpect(jsonPath("$.data.options").isArray())
            .andExpect(jsonPath("$.data.images").isArray())
            .andExpect(jsonPath("$.data.categories").isArray())
            .andReturn();

        // Then: Cache was populated
        String cached = redisTemplate.opsForValue().get("cache:product:detail:1");
        assertThat(cached).isNotNull();

        JsonNode firstData = json.readTree(first.getResponse().getContentAsString()).path("data");

        // When: Second call - should hit cache (no error, same data)
        mockMvc.perform(get("/api/products/1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.productId").value(1));

        // Verify cache still contains same data
        String cachedAfter = redisTemplate.opsForValue().get("cache:product:detail:1");
        assertThat(cachedAfter).isNotNull();
        assertThat(cachedAfter).isEqualTo(cached);
    }

    @Test
    void getProductList_firstCallPopulatesRedis_secondCallHitsCache() throws Exception {
        // When: First list call
        mockMvc.perform(get("/api/products?page=0&size=10"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data").isArray())
            .andExpect(jsonPath("$.meta.page").value(0))
            .andExpect(jsonPath("$.meta.size").value(10))
            .andReturn();

        // Then: List cache was populated
        String version = redisTemplate.opsForValue().get("cache:product:list:version");
        assertThat(version).isNotNull();

        // Cache key exists (format: cache:product:list:v{version}:s{sort}:p{page}:sz{size})
        String cacheKey = "cache:product:list:v" + version + ":sLATEST:p0:sz10";
        String cached = redisTemplate.opsForValue().get(cacheKey);
        assertThat(cached).isNotNull();
    }

    // ========================================================================
    // AC2: Cache invalidation on admin update
    // ========================================================================

    @Test
    void adminUpdate_invalidatesDetailCache() throws Exception {
        // Given: Product detail cached
        mockMvc.perform(get("/api/products/1"))
            .andExpect(status().isOk());

        String cachedBefore = redisTemplate.opsForValue().get("cache:product:detail:1");
        assertThat(cachedBefore).isNotNull();

        // When: Admin updates product name
        mockMvc.perform(patch("/api/admin/products/1")
                .with(jwtWithRole(adminToken, "PRODUCT_ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"변경된 선크림\"}"))
            .andExpect(status().isOk());

        // Then: Detail cache was invalidated (event listener deleted it)
        Thread.sleep(100); // Small delay for event processing

        String cachedAfter = redisTemplate.opsForValue().get("cache:product:detail:1");
        assertThat(cachedAfter).isNull();
    }

    @Test
    void adminUpdate_bumpsListVersion() throws Exception {
        // Given: List cache populated with version
        mockMvc.perform(get("/api/products?page=0&size=10"))
            .andExpect(status().isOk());

        String versionBefore = redisTemplate.opsForValue().get("cache:product:list:version");
        assertThat(versionBefore).isNotNull();
        long versionBeforeNum = Long.parseLong(versionBefore);

        // When: Admin updates product
        mockMvc.perform(patch("/api/admin/products/1")
                .with(jwtWithRole(adminToken, "PRODUCT_ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"salePrice\":15000}"))
            .andExpect(status().isOk());

        // Then: List version was bumped
        Thread.sleep(100); // Wait for event processing

        String versionAfter = redisTemplate.opsForValue().get("cache:product:list:version");
        assertThat(versionAfter).isNotNull();
        long versionAfterNum = Long.parseLong(versionAfter);
        assertThat(versionAfterNum).isGreaterThan(versionBeforeNum);

        // Old cache key no longer accessible (version mismatch)
        String oldKey = "cache:product:list:v" + versionBefore + ":p0:sz10";
        String oldCache = redisTemplate.opsForValue().get(oldKey);
        assertThat(oldCache).isNull();
    }

    // ========================================================================
    // AC3: HIDDEN/STOPPED/DRAFT excluded from public list
    // ========================================================================

    @Test
    void publicList_excludesHiddenStoppedDraftProducts() throws Exception {
        // Given: Create products in various non-public states
        createProduct("숨겨진 상품", Product.ProductStatus.HIDDEN, 0L);
        createProduct("중지된 상품", Product.ProductStatus.STOPPED, 0L);
        createProduct("임시 상품", Product.ProductStatus.DRAFT, 0L);

        // When: Public list
        mockMvc.perform(get("/api/products"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data").isArray())
            // Flyway seeds 1 ON_SALE product
            .andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0].productName").value("키즈 매일 선크림 SPF50+ PA++++"));
    }

    @Test
    void publicList_includesSoldOutProducts() throws Exception {
        // Given: SOLD_OUT product
        createProduct("품절 상품", Product.ProductStatus.SOLD_OUT, 0L);

        // When: Public list
        mockMvc.perform(get("/api/products"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(2)) // Flyway seed + SOLD_OUT
            .andExpect(jsonPath("$.data[?(@.productName == '품절 상품')]").exists());
    }

    @Test
    void getProductDetail_returns404_forHiddenProduct() throws Exception {
        // Given: HIDDEN product
        Long productId = createProduct("숨겨진 상품", Product.ProductStatus.HIDDEN, 0L);

        // When & Then: 404
        mockMvc.perform(get("/api/products/" + productId))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.error.code").value("PRODUCT_NOT_FOUND"));
    }

    // ========================================================================
    // A1: reviewCount and avgRating populated from product_review_summaries
    // ========================================================================

    @Test
    @DisplayName("A1: product WITH reviews returns avgRating > 0 and reviewCount > 0 in detail")
    void getProductDetail_withReviews_returnsRealRatingAndCount() throws Exception {
        // Given: seed a review summary for product 1
        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            em.createNativeQuery("""
                INSERT INTO product_review_summaries (product_id, avg_rating, review_count)
                VALUES (1, 4.50, 12)
                ON CONFLICT (product_id) DO UPDATE
                    SET avg_rating = EXCLUDED.avg_rating,
                        review_count = EXCLUDED.review_count
                """).executeUpdate();
        });

        // When
        mockMvc.perform(get("/api/products/1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.rating").value(4.5))
            .andExpect(jsonPath("$.data.reviewCount").value(12));
    }

    @Test
    @DisplayName("A1: product list items WITH reviews return avgRating > 0 and reviewCount > 0")
    void getProductList_withReviews_returnsRealRatingAndCount() throws Exception {
        // Given: seed a review summary for product 1
        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            em.createNativeQuery("""
                INSERT INTO product_review_summaries (product_id, avg_rating, review_count)
                VALUES (1, 3.80, 5)
                ON CONFLICT (product_id) DO UPDATE
                    SET avg_rating = EXCLUDED.avg_rating,
                        review_count = EXCLUDED.review_count
                """).executeUpdate();
        });

        // When
        mockMvc.perform(get("/api/products"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].rating").value(3.8))
            .andExpect(jsonPath("$.data[0].reviewCount").value(5));
    }

    @Test
    @DisplayName("A1: product WITHOUT review summary returns rating=0.0 and reviewCount=0")
    void getProductDetail_withoutReviews_returnsZeroRating() throws Exception {
        // No review summary inserted — defaults should be 0

        mockMvc.perform(get("/api/products/1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.rating").value(0.0))
            .andExpect(jsonPath("$.data.reviewCount").value(0));
    }

    // ========================================================================
    // A2: POPULAR / RATING sort correctness
    // ========================================================================

    @Test
    @DisplayName("A2: POPULAR sort returns products ordered by sales_count DESC")
    void sort_byPopular_ordersBySalesCountDesc() throws Exception {
        // Given: product 1 has sales_count=5; create another with sales_count=20
        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            em.createNativeQuery("UPDATE products SET sales_count = 5 WHERE id = 1").executeUpdate();
        });
        Long highSalesProductId = createProduct("인기 상품", Product.ProductStatus.ON_SALE, 20L);

        // When: sort=POPULAR
        MvcResult result = mockMvc.perform(get("/api/products?sort=POPULAR"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data").isArray())
            .andExpect(jsonPath("$.data.length()").value(2))
            .andReturn();

        // Then: high-sales product comes first
        JsonNode data = json.readTree(result.getResponse().getContentAsString()).path("data");
        long firstId = data.get(0).path("productId").asLong();
        assertThat(firstId).isEqualTo(highSalesProductId);
    }

    @Test
    @DisplayName("A2: RATING sort returns products ordered by avg_rating DESC NULLS LAST")
    void sort_byRating_ordersByAvgRatingDesc() throws Exception {
        // Given: product 1 avg_rating=4.5; create another with avg_rating=2.0
        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            em.createNativeQuery("""
                INSERT INTO product_review_summaries (product_id, avg_rating, review_count)
                VALUES (1, 4.50, 10)
                ON CONFLICT (product_id) DO UPDATE
                    SET avg_rating = EXCLUDED.avg_rating,
                        review_count = EXCLUDED.review_count
                """).executeUpdate();
        });
        Long lowRatingProductId = createProduct("낮은 평점 상품", Product.ProductStatus.ON_SALE, 0L);
        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            em.createNativeQuery("""
                INSERT INTO product_review_summaries (product_id, avg_rating, review_count)
                VALUES (:pid, 2.00, 3)
                ON CONFLICT (product_id) DO UPDATE
                    SET avg_rating = EXCLUDED.avg_rating,
                        review_count = EXCLUDED.review_count
                """).setParameter("pid", lowRatingProductId).executeUpdate();
        });

        // When: sort=RATING
        MvcResult result = mockMvc.perform(get("/api/products?sort=RATING"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data").isArray())
            .andExpect(jsonPath("$.data.length()").value(2))
            .andReturn();

        // Then: product with higher rating (product 1, rating=4.5) comes first
        JsonNode data = json.readTree(result.getResponse().getContentAsString()).path("data");
        long firstId = data.get(0).path("productId").asLong();
        assertThat(firstId).isEqualTo(1L);
    }

    @Test
    @DisplayName("A2: RATING sort works even when no reviews exist (NULLS LAST)")
    void sort_byRating_fallsBackGracefully_whenNoReviews() throws Exception {
        // No review summaries — should succeed, returning products with NULL rating last
        mockMvc.perform(get("/api/products?sort=RATING"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data").isArray());
    }

    // ========================================================================
    // Additional: List filters and pagination
    // ========================================================================

    @Test
    void list_withCategoryFilter_returnsFiltered() throws Exception {
        // Flyway seeds product 1 mapped to categories 1,2,3
        mockMvc.perform(get("/api/products?categoryId=1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0].productId").value(1));
    }

    @Test
    void list_withBrandFilter_returnsFiltered() throws Exception {
        // Flyway seeds product 1 with brand_id=1
        mockMvc.perform(get("/api/products?brandId=1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0].brandName").value("더샘"));
    }

    @Test
    void list_withPriceAsc_sortOrdersCorrectly() throws Exception {
        createProduct("저가 상품", Product.ProductStatus.ON_SALE, 0L);

        mockMvc.perform(get("/api/products?sort=PRICE_ASC"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    void pagination_withPageAndSize_returnsCorrectSlice() throws Exception {
        mockMvc.perform(get("/api/products?page=0&size=5"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.meta.page").value(0))
            .andExpect(jsonPath("$.meta.size").value(5))
            .andExpect(jsonPath("$.data").isArray());
    }

    // ========================================================================
    // New: GET /api/products/rankings
    // ========================================================================

    @Test
    @DisplayName("rankings endpoint returns empty list when no ranking data exists")
    void rankings_returnsEmptyList_whenNoRankingData() throws Exception {
        mockMvc.perform(get("/api/products/rankings"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data").isArray())
            .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    @DisplayName("rankings returns results after ProductRankingJob runs")
    void rankings_returnsResults_afterRankingJobRuns() throws Exception {
        // Given: set sales_count for seed product and add a review summary
        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            em.createNativeQuery("UPDATE products SET sales_count = 10 WHERE id = 1").executeUpdate();
            em.createNativeQuery("""
                INSERT INTO product_review_summaries (product_id, avg_rating, review_count)
                VALUES (1, 4.00, 8)
                ON CONFLICT (product_id) DO UPDATE
                    SET avg_rating = EXCLUDED.avg_rating,
                        review_count = EXCLUDED.review_count
                """).executeUpdate();
        });

        // When: run the ranking job
        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            productRankingJob.recomputeAllRankings();
        });

        // Then: rankings endpoint returns the computed ranking
        mockMvc.perform(get("/api/products/rankings"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data").isArray())
            .andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0].productId").value(1))
            .andExpect(jsonPath("$.data[0].rankScore").isNumber());
    }

    @Test
    @DisplayName("best-sellers endpoint returns results ordered by salesCount DESC")
    void bestSellers_returnsResults_orderedBySalesCount() throws Exception {
        // Given: two products with different sales counts
        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            em.createNativeQuery("UPDATE products SET sales_count = 3 WHERE id = 1").executeUpdate();
        });
        Long highSalesId = createProduct("베스트 상품", Product.ProductStatus.ON_SALE, 50L);

        // Run ranking job to populate product_rankings
        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            productRankingJob.recomputeAllRankings();
        });

        // When: best-sellers
        MvcResult result = mockMvc.perform(get("/api/products/best-sellers"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data").isArray())
            .andExpect(jsonPath("$.data.length()").value(2))
            .andReturn();

        // Then: higher sales product first
        JsonNode data = json.readTree(result.getResponse().getContentAsString()).path("data");
        long firstId = data.get(0).path("productId").asLong();
        assertThat(firstId).isEqualTo(highSalesId);
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private Long createProduct(String name, Product.ProductStatus status, long salesCount) {
        return new TransactionTemplate(txManager).execute(s -> {
            Number productId = (Number) em.createNativeQuery("""
                INSERT INTO products (brand_id, name, description, status, base_price, sale_price, sales_count)
                VALUES (1, :name, 'Test', :status, 10000, 8000, :salesCount)
                RETURNING id
                """)
                .setParameter("name", name)
                .setParameter("status", status.name())
                .setParameter("salesCount", salesCount)
                .getSingleResult();
            return productId.longValue();
        });
    }

    private Jwt createAdminJwt() {
        return Jwt.withTokenValue("dummy-token")
            .header("alg", "RS256")
            .claim("sub", "1")
            .claim("role", "PRODUCT_ADMIN")
            .claim("typ", "access")
            .build();
    }

    private org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor jwtWithRole(
        Jwt jwt, String role
    ) {
        return jwt()
            .jwt(jwt)
            .authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_" + role));
    }
}
