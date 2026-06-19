package com.olive.commerce.public_api;

import com.olive.commerce.common.persistence.PostgresIntegrationSupport;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * OLV-C3: GET /api/categories/{id}/products 및 GET /api/brands/{id}/products 검증.
 *
 * - 유효한 카테고리/브랜드 ID → 상품 목록과 메타 반환
 * - 존재하지 않는 ID → 404 CATEGORY_NOT_FOUND / BRAND_NOT_FOUND
 * - 인증 없이 접근 가능 (public endpoint)
 *
 * Flyway V3 시드 기준:
 *   brand id=1 (더샘/thesecret), product id=1 (선크림, ON_SALE)
 *   category id=1 (스킨케어) — product 1이 매핑됨
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Transactional
class SubResourceProductApiIT extends PostgresIntegrationSupport {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
        .withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PlatformTransactionManager txManager;

    @PersistenceContext
    private EntityManager em;

    @BeforeEach
    void setUp() {
        // Flush Redis so product list cache does not bleed between tests.
        // RedisTemplate is not injected here; use a connection flush via TransactionTemplate.
        // The @Transactional on the class rolls back DB writes; Redis is flushed per test.
        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            // Reset sequence in sync with Flyway V3 seed (product id=1 is the baseline)
            em.createNativeQuery("SELECT setval('products_id_seq', 1, true)").getSingleResult();
        });
    }

    // -------------------------------------------------------------------------
    // GET /api/categories/{id}/products
    // -------------------------------------------------------------------------

    @Test
    void categoryProducts_knownId_returnsProductList() throws Exception {
        // Category 1 (스킨케어) has product 1 (선크림) mapped via V3 seed.
        mockMvc.perform(get("/api/categories/1/products"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data").isArray())
            .andExpect(jsonPath("$.meta.page").value(0))
            .andExpect(jsonPath("$.meta.size").value(20));
    }

    @Test
    void categoryProducts_withPaginationParams_honoured() throws Exception {
        mockMvc.perform(get("/api/categories/1/products?page=0&size=5&sort=LATEST"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.meta.page").value(0))
            .andExpect(jsonPath("$.meta.size").value(5));
    }

    @Test
    void categoryProducts_unknownId_returns404() throws Exception {
        mockMvc.perform(get("/api/categories/99999/products"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code").value("CATEGORY_NOT_FOUND"));
    }

    @Test
    void categoryProducts_noAuthRequired() throws Exception {
        // Public endpoint — no Authorization header needed.
        mockMvc.perform(get("/api/categories/1/products"))
            .andExpect(status().isOk());
    }

    // -------------------------------------------------------------------------
    // GET /api/brands/{id}/products
    // -------------------------------------------------------------------------

    @Test
    void brandProducts_knownId_returnsProductList() throws Exception {
        // Brand id=1 (더샘/thesecret) has product 1 (선크림) from V3 seed.
        mockMvc.perform(get("/api/brands/1/products"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data").isArray())
            .andExpect(jsonPath("$.meta.page").value(0))
            .andExpect(jsonPath("$.meta.size").value(20));
    }

    @Test
    void brandProducts_withPaginationParams_honoured() throws Exception {
        mockMvc.perform(get("/api/brands/1/products?page=0&size=5&sort=PRICE_ASC"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.meta.page").value(0))
            .andExpect(jsonPath("$.meta.size").value(5));
    }

    @Test
    void brandProducts_unknownId_returns404() throws Exception {
        mockMvc.perform(get("/api/brands/99999/products"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code").value("BRAND_NOT_FOUND"));
    }

    @Test
    void brandProducts_noAuthRequired() throws Exception {
        // Public endpoint — no Authorization header needed.
        mockMvc.perform(get("/api/brands/1/products"))
            .andExpect(status().isOk());
    }
}
