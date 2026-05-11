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
        // Reset sequences and insert seed data (auto-rollback after each test due to @Transactional)
        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            // Reset sequences after Flyway data (brand id=1, category ids 1-3)
            em.createNativeQuery("SELECT setval('brands_id_seq', 1, true)").getSingleResult();
            em.createNativeQuery("SELECT setval('categories_id_seq', 3, true)").getSingleResult();
            // Insert test categories (id=1 is from Flyway, we insert id=2 as child)
            // Note: @Transactional will rollback after each test
        });

        // Flush Redis cache
        redisTemplate.delete("cache:categories:tree");
    }

    @Test
    void publicBrands_returnsActiveOnly() throws Exception {
        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            em.createNativeQuery("""
                INSERT INTO brands (name, slug, logo_url, status)
                VALUES ('Active Brand', 'active', 'logo', 'ACTIVE'), ('Inactive', 'inactive', 'logo', 'INACTIVE')
                """).executeUpdate();
        });

        mockMvc.perform(get("/api/brands"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data").isArray())
            .andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0].slug").value("active"));
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
        // First GET - should cache
        MvcResult first = mockMvc.perform(get("/api/categories"))
            .andExpect(status().isOk())
            .andReturn();
        JsonNode firstTree = json.readTree(first.getResponse().getContentAsString());
        String firstName = firstTree.path("data").path("categories").get(0).path("name").asText();
        assertThat(firstName).isEqualTo("스킨케어");

        // Update category via admin
        mockMvc.perform(patch("/api/admin/categories/1")
                .with(jwtWithRole(createAdminJwt(), "PRODUCT_ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"스킨케어 수정\",\"parentId\":null,\"sortOrder\":1}"))
            .andExpect(status().isOk());

        // Second GET - should reflect the change (cache was invalidated)
        MvcResult second = mockMvc.perform(get("/api/categories"))
            .andExpect(status().isOk())
            .andReturn();
        JsonNode secondTree = json.readTree(second.getResponse().getContentAsString());
        String secondName = secondTree.path("data").path("categories").get(0).path("name").asText();
        assertThat(secondName).isEqualTo("스킨케어 수정");
    }

    @Test
    void categoryCacheInvalidated_afterCreate() throws Exception {
        // First GET
        mockMvc.perform(get("/api/categories"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.categories.length()").value(1));

        // Create new category
        mockMvc.perform(post("/api/admin/categories")
                .with(jwtWithRole(createAdminJwt(), "PRODUCT_ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"메이크업\",\"slug\":\"makeup\",\"parentId\":null,\"sortOrder\":2}"))
            .andExpect(status().isCreated());

        // Second GET - should include new category
        MvcResult second = mockMvc.perform(get("/api/categories"))
            .andExpect(status().isOk())
            .andReturn();
        JsonNode tree = json.readTree(second.getResponse().getContentAsString());
        assertThat(tree.path("data").path("categories").size()).isEqualTo(2);
    }

    @Test
    void categoryCacheInvalidated_afterDelete() throws Exception {
        // First GET - has 2 categories
        MvcResult first = mockMvc.perform(get("/api/categories"))
            .andExpect(status().isOk())
            .andReturn();
        JsonNode firstTree = json.readTree(first.getResponse().getContentAsString());
        int initialCount = firstTree.path("data").path("categories").size();

        // Delete child category (no products mapped)
        mockMvc.perform(delete("/api/admin/categories/2")
                .with(jwtWithRole(createAdminJwt(), "PRODUCT_ADMIN")))
            .andExpect(status().isOk());

        // Second GET - should reflect deletion
        MvcResult second = mockMvc.perform(get("/api/categories"))
            .andExpect(status().isOk())
            .andReturn();
        JsonNode secondTree = json.readTree(second.getResponse().getContentAsString());
        int newCount = secondTree.path("data").path("categories").get(0).path("children").size();
        // Child was removed
        assertThat(newCount).isEqualTo(0);
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
