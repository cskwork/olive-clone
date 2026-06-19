package com.olive.commerce.common.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure unit tests for {@link RateLimitFilter}.
 *
 * <p>No Spring context is loaded. The {@link Environment} is stubbed so that
 * {@code matchesProfiles("test")} returns {@code false}, keeping the limiter active.
 * This lets us verify the 429 path without profile interference and without needing
 * {@code @DirtiesContext} or shared-context bucket contamination.
 */
class RateLimitFilterTest {

    private RateLimitFilter filter;
    private Environment environment;

    @BeforeEach
    void setUp() {
        RateLimitProperties props = new RateLimitProperties();
        props.setEnabled(true);
        RateLimitProperties.Auth auth = new RateLimitProperties.Auth();
        auth.setRequestsPerMinute(2);
        props.setAuth(auth);
        RateLimitProperties.Browse browse = new RateLimitProperties.Browse();
        browse.setRequestsPerMinute(300);
        props.setBrowse(browse);

        // Stub Environment so the test-profile bypass does NOT engage.
        environment = mock(Environment.class);
        when(environment.matchesProfiles("test")).thenReturn(false);

        filter = new RateLimitFilter(props, new ObjectMapper(), environment);
    }

    @Test
    @DisplayName("Requests within the bucket limit pass through to the filter chain")
    void withinLimit_requestsPassThrough() throws Exception {
        FilterChain chain = mock(FilterChain.class);

        for (int i = 0; i < 2; i++) {
            MockHttpServletRequest req = authRequest();
            MockHttpServletResponse res = new MockHttpServletResponse();
            filter.doFilter(req, res, chain);
            assertThat(res.getStatus()).isNotEqualTo(429);
        }

        verify(chain, times(2)).doFilter(any(), any());
    }

    @Test
    @DisplayName("Request exceeding the bucket limit returns 429 with RATE_LIMIT_EXCEEDED envelope")
    void exceededLimit_returns429WithErrorEnvelope() throws Exception {
        FilterChain chain = mock(FilterChain.class);

        // Exhaust the 2-token bucket.
        for (int i = 0; i < 2; i++) {
            filter.doFilter(authRequest(), new MockHttpServletResponse(), chain);
        }

        // Third request must be rejected by the filter — chain must NOT be called.
        MockHttpServletRequest blockedReq = authRequest();
        MockHttpServletResponse blockedRes = new MockHttpServletResponse();
        filter.doFilter(blockedReq, blockedRes, chain);

        assertThat(blockedRes.getStatus()).isEqualTo(429);
        assertThat(blockedRes.getHeader("Retry-After")).isEqualTo("60");
        String body = blockedRes.getContentAsString();
        assertThat(body).contains("\"success\":false");
        assertThat(body).contains("RATE_LIMIT_EXCEEDED");
        assertThat(body).contains("/api/auth/login");

        // The chain must have been called exactly twice (the two allowed requests).
        verify(chain, times(2)).doFilter(any(), any());
    }

    @Test
    @DisplayName("OPTIONS requests are never throttled (CORS preflight passthrough)")
    void optionsRequest_isNeverThrottled() throws Exception {
        FilterChain chain = mock(FilterChain.class);
        // Even after exhausting the bucket, OPTIONS must pass.
        for (int i = 0; i < 10; i++) {
            MockHttpServletRequest req = new MockHttpServletRequest("OPTIONS", "/api/auth/login");
            req.setRemoteAddr("127.0.0.1");
            MockHttpServletResponse res = new MockHttpServletResponse();
            filter.doFilter(req, res, chain);
            assertThat(res.getStatus()).isNotEqualTo(429);
        }
        verify(chain, times(10)).doFilter(any(), any());
    }

    @Test
    @DisplayName("Test-profile bypass: filter skips limiting when 'test' profile is active")
    void testProfileActive_bypassesRateLimit() throws Exception {
        // Re-stub environment to simulate the test profile being active.
        when(environment.matchesProfiles("test")).thenReturn(true);
        FilterChain chain = mock(FilterChain.class);

        // Send far more than the 2-token limit — all must pass.
        for (int i = 0; i < 20; i++) {
            MockHttpServletRequest req = authRequest();
            MockHttpServletResponse res = new MockHttpServletResponse();
            filter.doFilter(req, res, chain);
            assertThat(res.getStatus()).isNotEqualTo(429);
        }
        verify(chain, times(20)).doFilter(any(), any());
    }

    @Test
    @DisplayName("Non-auth non-browse paths are not filtered at all")
    void nonMatchingPath_isSkipped() throws Exception {
        FilterChain chain = mock(FilterChain.class);
        // Send many requests to a non-limited path.
        for (int i = 0; i < 50; i++) {
            MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/orders");
            req.setRemoteAddr("127.0.0.1");
            MockHttpServletResponse res = new MockHttpServletResponse();
            filter.doFilter(req, res, chain);
            assertThat(res.getStatus()).isNotEqualTo(429);
        }
        verify(chain, times(50)).doFilter(any(), any());
    }

    // -------------------------------------------------------------------------
    private static MockHttpServletRequest authRequest() {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/auth/login");
        req.setRemoteAddr("127.0.0.1");
        return req;
    }
}
