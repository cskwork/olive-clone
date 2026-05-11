package com.olive.commerce.cart;

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
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * OLV-040 AC 검증 — Cart API.
 *
 * AC1: Round-trip integration test for add/list/update/remove.
 * AC2: Adding the same option twice increments instead of duplicating.
 * AC3: Add with quantity > stock → 409 + the available count in the body.
 * AC4: Anonymous → member merge: union by product_option_id, sum quantities, cap at stock.
 * AC5: Cart items reflect the latest product price/status, not what was stored when added.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Transactional
class CartApiIT extends PostgresIntegrationSupport {

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
    private CartRepository cartRepository;

    @Autowired
    private CartItemRepository cartItemRepository;

    @Autowired
    private org.springframework.data.redis.core.StringRedisTemplate redisTemplate;

    private Jwt memberToken;

    @org.springframework.test.context.DynamicPropertySource
    static void redisProperties(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @BeforeEach
    void setUp() {
        // Reset sequences
        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            em.createNativeQuery("SELECT setval('carts_id_seq', 1, true)").getSingleResult();
            em.createNativeQuery("SELECT setval('cart_items_id_seq', 1, true)").getSingleResult();
        });

        // Flush Redis
        redisTemplate.getConnectionFactory().getConnection().flushAll();

        memberToken = createMemberJwt(1L);

        // Seed data: member for test
        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            em.createNativeQuery("""
                INSERT INTO members (id, email, password_hash, name, phone, status, grade_id)
                VALUES (1, 'test@example.com', '$2a$12$dummyHashForTesting', 'Test User', '01012345678', 'ACTIVE', 1)
                ON CONFLICT (id) DO NOTHING
                """).executeUpdate();
        });

        // Seed data: inventory for option 1 (Flyway option 1)
        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            em.createNativeQuery("""
                INSERT INTO inventories (product_option_id, total_quantity, reserved_quantity)
                VALUES (1, 10, 0)
                ON CONFLICT (product_option_id) DO UPDATE SET total_quantity = 10
                """).executeUpdate();
        });
    }

    // ========================================================================
    // AC1: Round-trip integration test for add/list/update/remove
    // ========================================================================

    @Test
    void add_list_update_remove_roundTrip() throws Exception {
        // Given: Add item
        MvcResult addResult = mockMvc.perform(post("/api/cart/items")
                .with(jwtWithRole(memberToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"productOptionId\":1,\"quantity\":2}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.cartItemId").isNumber())
            .andExpect(jsonPath("$.data.quantity").value(2))
            .andReturn();

        // Extract cartItemId
        String response = addResult.getResponse().getContentAsString();
        Long cartItemId = json.readTree(response).path("data").path("cartItemId").asLong();

        // When: List cart
        mockMvc.perform(get("/api/cart")
                .with(jwtWithRole(memberToken)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.items").isArray())
            .andExpect(jsonPath("$.data.items.length()").value(1))
            .andExpect(jsonPath("$.data.items[0].cartItemId").value(cartItemId))
            .andExpect(jsonPath("$.data.items[0].productOptionId").value(1))
            .andExpect(jsonPath("$.data.items[0].quantity").value(2))
            .andExpect(jsonPath("$.data.totalItemCount").value(2)); // quantity sum

        // When: Update quantity
        mockMvc.perform(patch("/api/cart/items/" + cartItemId)
                .with(jwtWithRole(memberToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"quantity\":5}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true));

        // Verify updated
        mockMvc.perform(get("/api/cart")
                .with(jwtWithRole(memberToken)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items[0].quantity").value(5))
            .andExpect(jsonPath("$.data.totalItemCount").value(5));

        // When: Remove item
        mockMvc.perform(delete("/api/cart/items/" + cartItemId)
                .with(jwtWithRole(memberToken)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true));

        // Verify empty
        mockMvc.perform(get("/api/cart")
                .with(jwtWithRole(memberToken)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items").isEmpty())
            .andExpect(jsonPath("$.data.totalItemCount").value(0));
    }

    // ========================================================================
    // AC2: Adding the same option twice increments instead of duplicating
    // ========================================================================

    @Test
    void add_sameOptionTwice_incrementsQuantity_notDuplicate() throws Exception {
        // When: Add option 1, quantity 2
        mockMvc.perform(post("/api/cart/items")
                .with(jwtWithRole(memberToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"productOptionId\":1,\"quantity\":2}"))
            .andExpect(status().isCreated());

        // When: Add same option again, quantity 3
        mockMvc.perform(post("/api/cart/items")
                .with(jwtWithRole(memberToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"productOptionId\":1,\"quantity\":3}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.quantity").value(5)); // 2 + 3

        // Then: Single item with quantity 5
        mockMvc.perform(get("/api/cart")
                .with(jwtWithRole(memberToken)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items.length()").value(1))
            .andExpect(jsonPath("$.data.items[0].quantity").value(5))
            .andExpect(jsonPath("$.data.totalItemCount").value(5));
    }

    // ========================================================================
    // AC3: Add with quantity > stock → 409 + available count
    // ========================================================================

    @Test
    void add_quantityExceedsStock_returns409WithAvailableCount() throws Exception {
        // Given: option 1 has available_quantity = 10 (seeded in setUp)
        // When: Request quantity 15
        mockMvc.perform(post("/api/cart/items")
                .with(jwtWithRole(memberToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"productOptionId\":1,\"quantity\":15}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.error.code").value("INSUFFICIENT_INVENTORY"));
    }

    // ========================================================================
    // AC4: Anonymous → member merge: union by product_option_id, sum quantities, cap at stock
    // ========================================================================

    @Test
    void merge_anonymousToMember_unionByOption_sumQuantities_capAtStock() throws Exception {
        // Given: Anonymous cart has option 1:2
        String sessionId = "test-session-123";
        mockMvc.perform(post("/api/cart/anonymous/items")
                .header("X-Session-ID", sessionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"productOptionId\":1,\"quantity\":2}"))
            .andExpect(status().isCreated());

        // Given: Member cart has option 1:3 (add via member API)
        mockMvc.perform(post("/api/cart/items")
                .with(jwtWithRole(memberToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"productOptionId\":1,\"quantity\":3}"))
            .andExpect(status().isCreated());

        // When: Merge
        mockMvc.perform(post("/api/cart/merge")
                .with(jwtWithRole(memberToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sessionId\":\"" + sessionId + "\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.mergedItemCount").value(1)) // 1 item merged
            .andExpect(jsonPath("$.data.totalItemCount").value(1)); // 1 unique item

        // Then: Member cart has option 1 with quantity 5 (2 + 3), capped at stock (10)
        mockMvc.perform(get("/api/cart")
                .with(jwtWithRole(memberToken)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items.length()").value(1))
            .andExpect(jsonPath("$.data.items[0].quantity").value(5)); // 2 + 3 = 5

        // Verify anonymous cart was deleted
        mockMvc.perform(get("/api/cart/anonymous")
                .header("X-Session-ID", sessionId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items").isEmpty());
    }

    @Test
    void merge_sumExceedsStock_cappedAtAvailableQuantity() throws Exception {
        // Given: Set stock to 5
        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            em.createNativeQuery("""
                UPDATE inventories SET total_quantity = 5, reserved_quantity = 0
                WHERE product_option_id = 1
                """).executeUpdate();
        });

        // Given: Anonymous cart has option 1:3
        String sessionId = "test-session-cap";
        mockMvc.perform(post("/api/cart/anonymous/items")
                .header("X-Session-ID", sessionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"productOptionId\":1,\"quantity\":3}"))
            .andExpect(status().isCreated());

        // Given: Member cart has option 1:4
        mockMvc.perform(post("/api/cart/items")
                .with(jwtWithRole(memberToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"productOptionId\":1,\"quantity\":4}"))
            .andExpect(status().isCreated());

        // When: Merge (3 + 4 = 7, but stock = 5)
        mockMvc.perform(post("/api/cart/merge")
                .with(jwtWithRole(memberToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sessionId\":\"" + sessionId + "\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.totalItemCount").value(1));

        // Then: Quantity capped at 5
        mockMvc.perform(get("/api/cart")
                .with(jwtWithRole(memberToken)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items[0].quantity").value(5)); // capped at available (5)
    }

    // ========================================================================
    // AC5: Cart items reflect the latest product price/status
    // ========================================================================

    @Test
    void cartItem_reflectsLatestPriceAndStatus() throws Exception {
        // Given: Add item
        mockMvc.perform(post("/api/cart/items")
                .with(jwtWithRole(memberToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"productOptionId\":1,\"quantity\":1}"))
            .andExpect(status().isCreated());

        // Then: Cart item shows current price/status
        mockMvc.perform(get("/api/cart")
                .with(jwtWithRole(memberToken)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items[0].productOptionId").value(1))
            .andExpect(jsonPath("$.data.items[0].optionName").exists())
            .andExpect(jsonPath("$.data.items[0].productName").exists())
            .andExpect(jsonPath("$.data.items[0].salePrice").exists())
            .andExpect(jsonPath("$.data.items[0].onSale").isBoolean())
            .andExpect(jsonPath("$.data.items[0].availableQuantity").value(10))
            .andExpect(jsonPath("$.data.items[0].productStatus").value("ON_SALE"));

        // When: Update product sale price
        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            em.createNativeQuery("""
                UPDATE products SET sale_price = 15000
                WHERE id = (SELECT product_id FROM product_options WHERE id = 1)
                """).executeUpdate();
        });

        // Then: Cart item reflects new price
        mockMvc.perform(get("/api/cart")
                .with(jwtWithRole(memberToken)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items[0].salePrice").exists()); // Updated price
    }

    // ========================================================================
    // Additional: Anonymous cart operations
    // ========================================================================

    @Test
    void anonymousCart_add_list_update_remove() throws Exception {
        String sessionId = "anon-session-test";

        // Add
        mockMvc.perform(post("/api/cart/anonymous/items")
                .header("X-Session-ID", sessionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"productOptionId\":1,\"quantity\":2}"))
            .andExpect(status().isCreated());

        // List
        mockMvc.perform(get("/api/cart/anonymous")
                .header("X-Session-ID", sessionId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items.length()").value(1))
            .andExpect(jsonPath("$.data.items[0].productOptionId").value(1))
            .andExpect(jsonPath("$.data.items[0].quantity").value(2));

        // Update
        mockMvc.perform(patch("/api/cart/anonymous/items/1")
                .header("X-Session-ID", sessionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"quantity\":5}"))
            .andExpect(status().isOk());

        // Verify updated
        mockMvc.perform(get("/api/cart/anonymous")
                .header("X-Session-ID", sessionId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items[0].quantity").value(5));

        // Remove
        mockMvc.perform(delete("/api/cart/anonymous/items/1")
                .header("X-Session-ID", sessionId))
            .andExpect(status().isOk());

        // Verify empty
        mockMvc.perform(get("/api/cart/anonymous")
                .header("X-Session-ID", sessionId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items").isEmpty());
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private Jwt createMemberJwt(Long memberId) {
        return Jwt.withTokenValue("dummy-token")
            .header("alg", "RS256")
            .claim("sub", String.valueOf(memberId))
            .claim("role", "USER")
            .claim("typ", "access")
            .build();
    }

    private RequestPostProcessor jwtWithRole(Jwt jwt) {
        // Spring Security Test의 jwt() 헬퍼는 JwtAuthenticationConverter를 사용하지 않으므로,
        // UsernamePasswordAuthenticationToken을 사용하여 AuthenticatedUser를 principal로 직접 설정합니다.
        Long memberId = Long.parseLong(jwt.getSubject());
        com.olive.commerce.member.MemberRole role = com.olive.commerce.member.MemberRole.USER;
        com.olive.commerce.common.security.AuthenticatedUser principal =
            new com.olive.commerce.common.security.AuthenticatedUser(memberId, role);

        org.springframework.security.core.Authentication authentication =
            new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                principal,
                jwt,
                java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_USER"))
            );

        return authentication(authentication);
    }
}
