package com.olive.commerce.common.security;

import com.olive.commerce.member.MemberRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * OLV-005 의 모든 AC 를 Spring Boot 컨텍스트 + MockMvc 로 검증한다.
 *
 * SecurityFilterChain 만 검증하면 충분하므로 DB 자동설정 3 종 (DataSource, JPA, Flyway) 을
 * 끈다 — Testcontainers 의존을 피해 보안 골격이 docker 가용성과 독립적으로 검증되게 한다.
 * 후속 도메인 티켓 (OLV-011 등) 은 DB 가 필요하므로 PostgresIntegrationSupport 를 사용한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@EnableAutoConfiguration(exclude = {
    DataSourceAutoConfiguration.class,
    DataSourceTransactionManagerAutoConfiguration.class,
    HibernateJpaAutoConfiguration.class,
    FlywayAutoConfiguration.class
})
@TestPropertySource(properties = {
    "spring.flyway.enabled=false",
    "spring.jpa.repositories.bootstrap-mode=default"
})
class SecurityFilterChainIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Test
    void actuatorHealth_isPublic_returns200() throws Exception {
        mockMvc.perform(get("/actuator/health"))
            .andExpect(status().isOk());
    }

    @Test
    void apiCart_withoutToken_returns401_andErrorEnvelope() throws Exception {
        mockMvc.perform(get("/api/cart"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.error.code").value("AUTHENTICATION_REQUIRED"))
            .andExpect(jsonPath("$.error.path").value("/api/cart"))
            .andExpect(jsonPath("$.error.traceId").isNotEmpty());
    }

    @Test
    void apiCart_withUserToken_returns200() throws Exception {
        String token = jwtTokenProvider.issueAccess(42L, MemberRole.USER);
        mockMvc.perform(get("/api/cart").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.memberId").value(42));
    }

    @Test
    void apiAdminProducts_withUserToken_returns403_andErrorEnvelope() throws Exception {
        String token = jwtTokenProvider.issueAccess(42L, MemberRole.USER);
        mockMvc.perform(post("/api/admin/products")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.error.code").value("ACCESS_DENIED"));
    }

    @Test
    void apiAdminProducts_withProductAdminToken_returns201() throws Exception {
        String token = jwtTokenProvider.issueAccess(7L, MemberRole.PRODUCT_ADMIN);
        mockMvc.perform(post("/api/admin/products")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.createdBy").value(7))
            .andExpect(jsonPath("$.data.role").value("PRODUCT_ADMIN"));
    }

    @Test
    void apiAdminProducts_withSuperAdminToken_passesHierarchy_returns201() throws Exception {
        String token = jwtTokenProvider.issueAccess(1L, MemberRole.SUPER_ADMIN);
        mockMvc.perform(post("/api/admin/products")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.role").value("SUPER_ADMIN"));
    }

    @Test
    void apiAdminProducts_withCsManagerToken_passesUrlButFailsMethodSecurity_returns403() throws Exception {
        String token = jwtTokenProvider.issueAccess(11L, MemberRole.CS_MANAGER);
        mockMvc.perform(post("/api/admin/products")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error.code").value("ACCESS_DENIED"));
    }

    @Test
    void apiAuth_login_isPublic_returns200() throws Exception {
        mockMvc.perform(post("/api/auth/login"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("placeholder"));
    }

    @Test
    void apiCart_withTamperedToken_returns401() throws Exception {
        String token = jwtTokenProvider.issueAccess(42L, MemberRole.USER);
        // Modify a char inside the signature segment (skipping last char which only
        // encodes 2 useful bits for RS256-2048).
        int lastDot = token.lastIndexOf('.');
        int target = lastDot + (token.length() - lastDot) / 2;
        char swapped = token.charAt(target) == 'A' ? 'B' : 'A';
        String tampered = token.substring(0, target) + swapped + token.substring(target + 1);

        mockMvc.perform(get("/api/cart").header("Authorization", "Bearer " + tampered))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error.code").value("AUTHENTICATION_REQUIRED"));
    }
}
