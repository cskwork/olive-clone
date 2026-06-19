package com.olive.commerce.common.web;

import com.olive.commerce.auth.LoginAttemptGuard;
import com.olive.commerce.member.MemberGradeRepository;
import com.olive.commerce.member.MemberLoginHistoryRepository;
import com.olive.commerce.member.MemberRefreshTokenRepository;
import com.olive.commerce.member.MemberRepository;
import com.olive.testsupport.security.SecurityFilterChainTestApp;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CORS preflight integration tests (B2b).
 *
 * <p>Does NOT start Testcontainers. Rate-limit behaviour is verified in
 * {@link RateLimitFilterTest} (pure unit test) to avoid shared-context bucket
 * contamination and eliminate the need for {@code @DirtiesContext}.
 */
@SpringBootTest(classes = SecurityFilterChainTestApp.class)
@AutoConfigureMockMvc
@EnableAutoConfiguration(exclude = {
    DataSourceAutoConfiguration.class,
    DataSourceTransactionManagerAutoConfiguration.class,
    HibernateJpaAutoConfiguration.class,
    FlywayAutoConfiguration.class
})
@TestPropertySource(properties = {
    "spring.flyway.enabled=false",
    "spring.jpa.repositories.bootstrap-mode=default",
    "management.endpoint.health.probes.enabled=false",
    "management.endpoint.health.validate-group-members=false",
    "management.endpoint.health.group.readiness.include=ping",
    "management.endpoint.health.group.liveness.include=ping",
    "management.health.redis.enabled=false",
    "olive.security.jwt.access-ttl=PT30M",
    "olive.security.jwt.refresh-ttl=P14D",
    "olive.cors.allowed-origins=http://localhost:5173"
})
class RateLimitAndCorsIT {

    @Autowired
    private MockMvc mockMvc;

    // Mocked because SecurityFilterChainTestApp's component scan pulls them in.
    @MockBean MemberRepository memberRepository;
    @MockBean MemberRefreshTokenRepository memberRefreshTokenRepository;
    @MockBean MemberLoginHistoryRepository memberLoginHistoryRepository;
    @MockBean MemberGradeRepository memberGradeRepository;
    @MockBean LoginAttemptGuard loginAttemptGuard;

    @Test
    @DisplayName("CORS preflight: allowed origin receives Access-Control-Allow-Origin header")
    void corsPreflight_allowedOrigin_returnsAccessControlHeader() throws Exception {
        mockMvc.perform(options("/api/auth/login")
                .header("Origin", "http://localhost:5173")
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "Content-Type"))
            .andExpect(status().isOk())
            .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));
    }

    @Test
    @DisplayName("CORS preflight: disallowed origin does not receive Access-Control-Allow-Origin")
    void corsPreflight_disallowedOrigin_noAccessControlHeader() throws Exception {
        mockMvc.perform(options("/api/auth/login")
                .header("Origin", "http://evil.example.com")
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "Content-Type"))
            .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }
}
