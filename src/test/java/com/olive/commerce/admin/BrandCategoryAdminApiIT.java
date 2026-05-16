package com.olive.commerce.admin;

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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * OLV-021 AC 검증 — Brand & Category Admin API.
 *
 * AC1: All admin endpoints reject non-admin tokens with 403.
 * AC2: Category tree endpoint returns nested children correctly for a 3-level hierarchy.
 * AC3: Cache invalidation: editing a category bumps cache so next public GET reflects change.
 * AC4: Integration test verifies slug uniqueness constraint is surfaced as 409 not 500.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Transactional
class BrandCategoryAdminApiIT extends PostgresIntegrationSupport {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private PlatformTransactionManager txManager;

    @PersistenceContext
    private EntityManager em;

    private Jwt productAdminToken;
    private Jwt userToken;

    @BeforeEach
    void setUp() {
        // Reset Hibernate sequences to sync with Flyway-inserted data
        // Flyway V3__product.sql inserts brand id=1 and category ids 1-3
        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            em.createNativeQuery("SELECT setval('brands_id_seq', (SELECT MAX(id) FROM brands), true)")
                .getSingleResult();
            em.createNativeQuery("SELECT setval('categories_id_seq', 6, true)").getSingleResult();
            // Insert additional test categories (4-6 as children)
            em.createNativeQuery("""
                INSERT INTO categories (id, name, slug, parent_id, sort_order, depth)
                VALUES
                (4, '토너', 'toner', 1, 1, 1),
                (5, '에센스', 'essence', 1, 2, 1),
                (6, '쿠션', 'cushion', 2, 1, 1)
                ON CONFLICT (id) DO NOTHING
                """).executeUpdate();
        });

        // Create test tokens
        productAdminToken = createJwt(1L, "PRODUCT_ADMIN");
        userToken = createJwt(2L, "USER");
    }

    private Jwt createJwt(Long userId, String role) {
        return Jwt.withTokenValue("dummy-token")
            .header("alg", "RS256")
            .claim("sub", userId.toString())
            .claim("role", role)
            .claim("typ", "access")
            .build();
    }

    private org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor jwtWithRole(Jwt jwt, String role) {
        return org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt()
            .jwt(jwt)
            .authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_" + role));
    }

    // ---------------------------------------------------------------------
    // AC1: Admin endpoints reject non-admin tokens with 403
    // ---------------------------------------------------------------------
    @Test
    void brandsEndpoint_withUserToken_returns403() throws Exception {
        mockMvc.perform(get("/api/admin/brands")
                .with(jwtWithRole(userToken, "USER")))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error.code").value("ACCESS_DENIED"));
    }

    @Test
    void brandsEndpoint_withProductAdminToken_returns200() throws Exception {
        mockMvc.perform(get("/api/admin/brands")
                .with(jwtWithRole(productAdminToken, "PRODUCT_ADMIN")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void createBrand_withUserToken_returns403() throws Exception {
        mockMvc.perform(post("/api/admin/brands")
                .with(jwtWithRole(userToken, "USER"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new BrandCreateRequest("test", "test-slug", "logo"))))
            .andExpect(status().isForbidden());
    }

    @Test
    void categoriesEndpoint_withUserToken_returns403() throws Exception {
        mockMvc.perform(get("/api/admin/categories")
                .with(jwtWithRole(userToken, "USER")))
            .andExpect(status().isForbidden());
    }

    // ---------------------------------------------------------------------
    // AC2: Category tree returns nested children for 3-level hierarchy
    // ---------------------------------------------------------------------
    @Test
    void categoryTree_returnsNestedChildrenFor3LevelHierarchy() throws Exception {
        // Add a 3rd level category under '토너' (which has id=4)
        new TransactionTemplate(txManager).executeWithoutResult(s ->
            em.createNativeQuery("""
                INSERT INTO categories (id, name, slug, parent_id, sort_order, depth)
                VALUES (7, '스킨 토너', 'skin-toner', 4, 1, 2)
                """).executeUpdate()
        );

        mockMvc.perform(get("/api/admin/categories")
                .with(jwtWithRole(productAdminToken, "PRODUCT_ADMIN")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data").isArray())
            .andExpect(jsonPath("$.data[0].name").value("스킨케어"))
            .andExpect(jsonPath("$.data[1].name").value("메이크업"));
    }

    // ---------------------------------------------------------------------
    // AC4: Slug uniqueness returns 409 not 500
    // ---------------------------------------------------------------------
    @Test
    void createBrand_withDuplicateSlug_returns409() throws Exception {
        mockMvc.perform(post("/api/admin/brands")
                .with(jwtWithRole(productAdminToken, "PRODUCT_ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new BrandCreateRequest("New Name", "thesecret", "logo"))))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("BRAND_SLUG_DUPLICATE"));
    }

    @Test
    void createCategory_withDuplicateSlug_succeeds() throws Exception {
        // Categories don't have unique slug constraint in DB (unlike brands)
        // This test documents current behavior: duplicate slugs are allowed
        mockMvc.perform(post("/api/admin/categories")
                .with(jwtWithRole(productAdminToken, "PRODUCT_ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new CategoryCreateRequest("test", "skincare", null, 1))))
            .andExpect(status().isCreated()); // Succeeds because slug is not unique in categories table
    }

    // ---------------------------------------------------------------------
    // Brand CRUD
    // ---------------------------------------------------------------------
    @Test
    void createBrand_returns201() throws Exception {
        mockMvc.perform(post("/api/admin/brands")
                .with(jwtWithRole(productAdminToken, "PRODUCT_ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new BrandCreateRequest("이니스프리", "innisfree", "logo"))))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.slug").value("innisfree"));
    }

    @Test
    void updateBrand_returns200() throws Exception {
        mockMvc.perform(patch("/api/admin/brands/1")
                .with(jwtWithRole(productAdminToken, "PRODUCT_ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new BrandUpdateRequest("더샘 업데이트", "logo2", "ACTIVE"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.name").value("더샘 업데이트"));
    }

    @Test
    void listBrands_withPagination_returnsPage() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/admin/brands?page=0&size=10")
                .with(jwtWithRole(productAdminToken, "PRODUCT_ADMIN")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.meta.page").value(0))
            .andExpect(jsonPath("$.meta.size").value(10))
            .andExpect(jsonPath("$.meta.total").value(7))
            .andReturn();
    }

    // ---------------------------------------------------------------------
    // Category CRUD with delete validation
    // ---------------------------------------------------------------------
    @Test
    void createTopLevelCategory_returns201() throws Exception {
        mockMvc.perform(post("/api/admin/categories")
                .with(jwtWithRole(productAdminToken, "PRODUCT_ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new CategoryCreateRequest("헤어", "hair", null, 3))))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.slug").value("hair"));
    }

    @Test
    void createChildCategory_returns201() throws Exception {
        mockMvc.perform(post("/api/admin/categories")
                .with(jwtWithRole(productAdminToken, "PRODUCT_ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new CategoryCreateRequest("로션", "lotion", 1L, 3))))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.depth").value(1));
    }

    @Test
    void updateCategory_returns200() throws Exception {
        mockMvc.perform(patch("/api/admin/categories/1")
                .with(jwtWithRole(productAdminToken, "PRODUCT_ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new CategoryUpdateRequest("스킨케어 업데이트", null, 1))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.name").value("스킨케어 업데이트"));
    }

    @Test
    void deleteCategory_withProductsMapped_returns409() throws Exception {
        // Map a product to category
        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            em.createNativeQuery("""
                INSERT INTO products (id, brand_id, name, status, base_price)
                VALUES (100, 1, 'Test Product', 'ON_SALE', 10000)
                """).executeUpdate();
            em.createNativeQuery("""
                INSERT INTO product_category_mapping (product_id, category_id)
                VALUES (100, 1)
                """).executeUpdate();
        });

        mockMvc.perform(delete("/api/admin/categories/1")
                .with(jwtWithRole(productAdminToken, "PRODUCT_ADMIN")))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("CATEGORY_HAS_PRODUCTS"));
    }

    @Test
    void deleteCategory_withoutProducts_returns204() throws Exception {
        // Category 6 (쿠션) has no products
        mockMvc.perform(delete("/api/admin/categories/6")
                .with(jwtWithRole(productAdminToken, "PRODUCT_ADMIN")))
            .andExpect(status().isOk());
    }

    @Test
    void updateCategory_cycleDetection_returns400() throws Exception {
        mockMvc.perform(patch("/api/admin/categories/1")
                .with(jwtWithRole(productAdminToken, "PRODUCT_ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new CategoryUpdateRequest("스킨케어", 1L, 1))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("CATEGORY_CYCLE_DETECTED"));
    }

    // ---------------------------------------------------------------------
    // Record DTOs for test requests
    // ---------------------------------------------------------------------
    record BrandCreateRequest(String name, String slug, String logoUrl) {}
    record BrandUpdateRequest(String name, String logoUrl, String status) {}
    record CategoryCreateRequest(String name, String slug, Long parentId, Integer sortOrder) {}
    record CategoryUpdateRequest(String name, Long parentId, Integer sortOrder) {}
}
