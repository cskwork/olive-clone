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
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AC4 — Rate-limit enforcement integration test.
 *
 * <p>Proves that the rate limiter actually fires in the running security filter chain
 * when {@code olive.ratelimit.force-active=true} overrides the test-profile bypass.
 * Uses a low auth limit (3 requests/minute) so the test runs quickly.
 *
 * <p>The general test suite keeps {@code force-active=false} (the default), so it is
 * not affected by this class.
 */
@SpringBootTest(classes = SecurityFilterChainTestApp.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
// Each test method gets a fresh context so bucket state from one test does not bleed into another.
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
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
    "olive.cors.allowed-origins=http://localhost:5173",
    // Force the rate limiter active in the test profile to prove AC4 end-to-end.
    "olive.ratelimit.force-active=true",
    // Low limit so the test terminates quickly — 3 requests/minute on the auth tier.
    "olive.ratelimit.auth.requests-per-minute=3"
})
class RateLimitEnforcementIT {

    @Autowired
    private MockMvc mockMvc;

    @MockBean MemberRepository memberRepository;
    @MockBean MemberRefreshTokenRepository memberRefreshTokenRepository;
    @MockBean MemberLoginHistoryRepository memberLoginHistoryRepository;
    @MockBean MemberGradeRepository memberGradeRepository;
    @MockBean LoginAttemptGuard loginAttemptGuard;

    private static final String VALID_BODY =
        "{\"email\":\"test@example.com\",\"password\":\"secret\"}";

    @Test
    @DisplayName("Auth endpoint: requests within the limit are not throttled")
    void withinLimit_requestsAreAllowed() throws Exception {
        // 3 requests exactly at the limit (capacity = 3).
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(post("/api/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(VALID_BODY))
                // May be 200 or 400 (validation) — not 429.
                .andExpect(result ->
                    assertThat(result.getResponse().getStatus()).isNotEqualTo(429));
        }
    }

    @Test
    @DisplayName("Auth endpoint: request over the limit returns 429 with ApiResponse error envelope")
    void overLimit_returns429WithErrorEnvelope() throws Exception {
        // Exhaust the 3-token bucket.
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(post("/api/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(VALID_BODY));
        }

        // The 4th request must be rejected with 429.
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_BODY))
            .andExpect(status().isTooManyRequests())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.error.code").value("RATE_LIMIT_EXCEEDED"))
            .andReturn();

        // Retry-After header must be present.
        assertThat(result.getResponse().getHeader("Retry-After")).isEqualTo("60");
    }
}
