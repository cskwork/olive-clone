package com.olive.commerce.wishlist;

import com.olive.commerce.common.persistence.PostgresIntegrationSupport;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * OLV-W01 AC 검증 — Wishlist (찜) API.
 *
 * AC1: add → appears in list.
 * AC2: add same product twice → still one entry (idempotent, no error).
 * AC3: remove → gone from list.
 * AC4: add non-existent product → 404/PRODUCT_NOT_FOUND.
 * AC5: list is per-member (another member does not see it).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class WishlistApiIT extends PostgresIntegrationSupport {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PlatformTransactionManager txManager;

    @PersistenceContext
    private EntityManager em;

    // member 1 is the primary test member; member 2 is the isolation member.
    private static final long MEMBER_1_ID = 100L;
    private static final long MEMBER_2_ID = 101L;

    // Product IDs seeded by Flyway (V3 + V15). We query at runtime to stay robust.
    private long productId1;
    private long productId2;

    @BeforeEach
    void setUp() {
        // Seed test members.
        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            em.createNativeQuery("""
                INSERT INTO members (id, email, password_hash, name, phone, status, grade_id)
                VALUES (100, 'wishlist1@example.com', '$2a$12$dummyHash', 'Member One', '01011110001', 'ACTIVE', 1)
                ON CONFLICT (id) DO NOTHING
                """).executeUpdate();
            em.createNativeQuery("""
                INSERT INTO members (id, email, password_hash, name, phone, status, grade_id)
                VALUES (101, 'wishlist2@example.com', '$2a$12$dummyHash', 'Member Two', '01011110002', 'ACTIVE', 1)
                ON CONFLICT (id) DO NOTHING
                """).executeUpdate();
        });

        // Resolve product IDs from seeded catalog.
        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            @SuppressWarnings("unchecked")
            List<Number> ids = em.createNativeQuery(
                "SELECT id FROM products WHERE status = 'ON_SALE' ORDER BY id LIMIT 2"
            ).getResultList();
            assertThat(ids).hasSizeGreaterThanOrEqualTo(2);
            productId1 = ids.get(0).longValue();
            productId2 = ids.get(1).longValue();
        });
    }

    // ========================================================================
    // AC1: add → appears in list
    // ========================================================================

    @Test
    void add_productAppearsInList() throws Exception {
        // When: Add product 1 to wishlist
        mockMvc.perform(post("/api/me/wishlist")
                .with(memberAuth(MEMBER_1_ID))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"productId\":" + productId1 + "}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.success").value(true));

        // Then: Product appears in list
        mockMvc.perform(get("/api/me/wishlist")
                .with(memberAuth(MEMBER_1_ID)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data").isArray())
            .andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0].productId").value(productId1))
            .andExpect(jsonPath("$.data[0].wishlistItemId").isNumber())
            .andExpect(jsonPath("$.meta.total").value(1));
    }

    // ========================================================================
    // AC2: add same product twice → still one entry (idempotent, no error)
    // ========================================================================

    @Test
    void add_sameProductTwice_idempotent_singleEntry() throws Exception {
        // When: Add product 1 twice
        mockMvc.perform(post("/api/me/wishlist")
                .with(memberAuth(MEMBER_1_ID))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"productId\":" + productId1 + "}"))
            .andExpect(status().isCreated());

        mockMvc.perform(post("/api/me/wishlist")
                .with(memberAuth(MEMBER_1_ID))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"productId\":" + productId1 + "}"))
            .andExpect(status().isCreated());

        // Then: Only one entry in list
        mockMvc.perform(get("/api/me/wishlist")
                .with(memberAuth(MEMBER_1_ID)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.meta.total").value(1));
    }

    // ========================================================================
    // AC3: remove → gone from list
    // ========================================================================

    @Test
    void remove_productDisappearsFromList() throws Exception {
        // Given: Add two products
        mockMvc.perform(post("/api/me/wishlist")
                .with(memberAuth(MEMBER_1_ID))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"productId\":" + productId1 + "}"))
            .andExpect(status().isCreated());

        mockMvc.perform(post("/api/me/wishlist")
                .with(memberAuth(MEMBER_1_ID))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"productId\":" + productId2 + "}"))
            .andExpect(status().isCreated());

        // Verify two entries
        mockMvc.perform(get("/api/me/wishlist")
                .with(memberAuth(MEMBER_1_ID)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(2));

        // When: Remove product 1
        mockMvc.perform(delete("/api/me/wishlist/" + productId1)
                .with(memberAuth(MEMBER_1_ID)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true));

        // Then: Only product 2 remains
        mockMvc.perform(get("/api/me/wishlist")
                .with(memberAuth(MEMBER_1_ID)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0].productId").value(productId2))
            .andExpect(jsonPath("$.meta.total").value(1));
    }

    // ========================================================================
    // AC4: add non-existent product → 404/PRODUCT_NOT_FOUND
    // ========================================================================

    @Test
    void add_nonExistentProduct_returns404() throws Exception {
        long nonExistentProductId = 999_999L;

        mockMvc.perform(post("/api/me/wishlist")
                .with(memberAuth(MEMBER_1_ID))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"productId\":" + nonExistentProductId + "}"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.error.code").value("PRODUCT_NOT_FOUND"));
    }

    // ========================================================================
    // AC5: list is per-member (another member doesn't see it)
    // ========================================================================

    @Test
    void list_perMemberIsolation_otherMemberDoesNotSeeItems() throws Exception {
        // Given: Member 1 adds a product
        mockMvc.perform(post("/api/me/wishlist")
                .with(memberAuth(MEMBER_1_ID))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"productId\":" + productId1 + "}"))
            .andExpect(status().isCreated());

        // Member 1 sees the item
        mockMvc.perform(get("/api/me/wishlist")
                .with(memberAuth(MEMBER_1_ID)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(1));

        // Member 2 sees an empty list
        mockMvc.perform(get("/api/me/wishlist")
                .with(memberAuth(MEMBER_2_ID)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data").isArray())
            .andExpect(jsonPath("$.data.length()").value(0))
            .andExpect(jsonPath("$.meta.total").value(0));
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private RequestPostProcessor memberAuth(long memberId) {
        com.olive.commerce.member.MemberRole role = com.olive.commerce.member.MemberRole.USER;
        com.olive.commerce.common.security.AuthenticatedUser principal =
            new com.olive.commerce.common.security.AuthenticatedUser(memberId, role);

        Authentication auth = new UsernamePasswordAuthenticationToken(
            principal,
            null,
            List.of(new SimpleGrantedAuthority("ROLE_USER"))
        );

        return authentication(auth);
    }
}
