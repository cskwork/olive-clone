package com.olive.commerce.public_api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.olive.commerce.common.persistence.PostgresIntegrationSupport;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * OLV-021 AC3 검증 — Public API + Redis Cache Invalidation.
 *
 * AC3: Cache invalidation — editing a category bumps cache:categories:tree
 *       so the next public GET reflects the change within one request.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Transactional
class BrandCategoryPublicApiIT extends PostgresIntegrationSupport {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
        .withExposedPorts(6379);

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

    private Jwt adminToken;

    @org.springframework.test.context.DynamicPropertySource
    static void redisProperties(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @BeforeEach
    void setUp() {
        // Reset sequences to sync with Flyway-inserted data (brand id=1, category ids 1-3)
        // Note: @Transactional will rollback after each test, but Flyway data persists
        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            em.createNativeQuery("SELECT setval('brands_id_seq', 1, true)").getSingleResult();
            em.createNativeQuery("SELECT setval('categories_id_seq', 3, true)").getSingleResult();
        });

        // Flush Redis cache
        redisTemplate.delete("cache:categories:tree");
    }

    @Test
    void publicBrands_returnsActiveOnly() throws Exception {
        // Flyway already inserted '더샘' (ACTIVE) brand
        // Add an INACTIVE brand to verify it's filtered out
        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            em.createNativeQuery("""
                INSERT INTO brands (name, slug, logo_url, status)
                VALUES ('Inactive', 'inactive', 'logo', 'INACTIVE')
                """).executeUpdate();
        });

        mockMvc.perform(get("/api/brands"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data").isArray())
            .andExpect(jsonPath("$.data.length()").value(1)) // Only '더샘' (ACTIVE) from Flyway
            .andExpect(jsonPath("$.data[0].slug").value("thesecret"));
    }

    @Test
    void publicCategories_withPagination() throws Exception {
        mockMvc.perform(get("/api/brands?page=0&size=10"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.meta.page").value(0))
            .andExpect(jsonPath("$.meta.size").value(10));
    }

    @Test
    void categoryTreeCache_warmedOnFirstGet() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/categories"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.categories").isArray())
            .andReturn();

        JsonNode tree = json.readTree(result.getResponse().getContentAsString());
        assertThat(tree.path("data").path("categories").isArray()).isTrue();
    }

    @Test
    void categoryCacheInvalidated_afterUpdate() throws Exception {
        // First GET - should cache (Flyway inserts 3 top-level categories)
        MvcResult first = mockMvc.perform(get("/api/categories"))
            .andExpect(status().isOk())
            .andReturn();
        JsonNode firstTree = json.readTree(first.getResponse().getContentAsString());
        int firstChildrenSize = firstTree.path("data").path("categories").get(0).path("children").size();
        assertThat(firstChildrenSize).isEqualTo(0);  // All Flyway categories are top-level (no children)

        // Update category 2 (메이크업) to be a child of category 1 (스킨케어)
        mockMvc.perform(patch("/api/admin/categories/2")
                .with(jwtWithRole(createAdminJwt(), "PRODUCT_ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .characterEncoding("UTF-8")
                .content("{\"name\":\"메이크업\",\"parentId\":1,\"sortOrder\":1}"))
            .andExpect(status().isOk());

        // Second GET - should reflect the change (cache was invalidated)
        // Category 1 (스킨케어) should now have 1 child
        MvcResult second = mockMvc.perform(get("/api/categories"))
            .andExpect(status().isOk())
            .andReturn();
        JsonNode secondTree = json.readTree(second.getResponse().getContentAsString());
        int secondChildrenSize = secondTree.path("data").path("categories").get(0).path("children").size();
        assertThat(secondChildrenSize).isEqualTo(1);  // 메이크업 is now a child of 스킨케어
    }

    @Test
    void categoryCacheInvalidated_afterCreate() throws Exception {
        // First GET - Flyway inserts 3 categories already
        mockMvc.perform(get("/api/categories"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.categories.length()").value(3));

        // Create new category (4번째)
        mockMvc.perform(post("/api/admin/categories")
                .with(jwtWithRole(createAdminJwt(), "PRODUCT_ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .characterEncoding("UTF-8")
                .content("{\"name\":\"향수\",\"slug\":\"perfume\",\"parentId\":null,\"sortOrder\":4}"))
            .andExpect(status().isCreated());

        // Second GET - should include new category (4 total)
        MvcResult second = mockMvc.perform(get("/api/categories"))
            .andExpect(status().isOk())
            .andReturn();
        JsonNode tree = json.readTree(second.getResponse().getContentAsString());
        assertThat(tree.path("data").path("categories").size()).isEqualTo(4);
    }

    @Test
    void categoryCacheInvalidated_afterDelete() throws Exception {
        // First GET - Flyway inserts 3 categories
        MvcResult first = mockMvc.perform(get("/api/categories"))
            .andExpect(status().isOk())
            .andReturn();
        JsonNode firstTree = json.readTree(first.getResponse().getContentAsString());
        int initialCount = firstTree.path("data").path("categories").size();
        assertThat(initialCount).isEqualTo(3);

        // Create a new category with no products (will be id=4)
        mockMvc.perform(post("/api/admin/categories")
                .with(jwtWithRole(createAdminJwt(), "PRODUCT_ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .characterEncoding("UTF-8")
                .content("{\"name\":\"임시카테고리\",\"slug\":\"temp\",\"parentId\":null,\"sortOrder\":99}"))
            .andExpect(status().isCreated());

        // Verify we now have 4 categories
        mockMvc.perform(get("/api/categories"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.categories.length()").value(4));

        // Delete the new category (no products mapped)
        mockMvc.perform(delete("/api/admin/categories/4")
                .with(jwtWithRole(createAdminJwt(), "PRODUCT_ADMIN")))
            .andExpect(status().isOk());

        // Second GET - should reflect deletion (back to 3)
        MvcResult second = mockMvc.perform(get("/api/categories"))
            .andExpect(status().isOk())
            .andReturn();
        JsonNode secondTree = json.readTree(second.getResponse().getContentAsString());
        int newCount = secondTree.path("data").path("categories").size();
        assertThat(newCount).isEqualTo(3);
    }

    private Jwt createAdminJwt() {
        return Jwt.withTokenValue("dummy-token")
            .header("alg", "RS256")
            .claim("sub", "1")
            .claim("role", "PRODUCT_ADMIN")
            .claim("typ", "access")
            .build();
    }

    private org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor jwtWithRole(Jwt jwt, String role) {
        return org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt()
            .jwt(jwt)
            .authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_" + role));
    }
}
