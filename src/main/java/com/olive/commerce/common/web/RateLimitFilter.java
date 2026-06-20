package com.olive.commerce.common.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.olive.commerce.common.api.ApiResponse;
import com.olive.commerce.common.error.ErrorBody;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Per-IP token-bucket rate limiter applied to high-traffic public endpoints.
 *
 * <p>Two limit tiers are supported:
 * <ul>
 *   <li><b>auth</b>  — /api/auth/**   (strict, prevents credential-stuffing)</li>
 *   <li><b>browse</b>— /api/search/** and /api/products/** (lenient crawl guard)</li>
 * </ul>
 *
 * <p><b>Profile gating</b>: the limiter is bypassed when the {@code test} Spring profile
 * is active AND {@code olive.ratelimit.force-active} is {@code false} (default).
 * Set {@code force-active=true} in a {@code @TestPropertySource} to enable enforcement
 * in targeted rate-limit integration tests (AC4) without affecting the general suite.
 *
 * <p><b>Bounded maps</b>: per-IP bucket caches are capped at {@value #MAX_BUCKETS} entries
 * using an access-ordered {@link LinkedHashMap} with LRU eviction to prevent memory leaks
 * under sustained traffic from many distinct IPs (DoS mitigation).
 *
 * <p><b>IP resolution</b>: uses {@link HttpServletRequest#getRemoteAddr()} only.
 * X-Forwarded-For is intentionally ignored because it is trivially spoofable.
 * Configure X-Forwarded-For handling at the proxy level rather than trusting headers.
 *
 * <p>The limiter is also disabled when {@code olive.ratelimit.enabled=false}.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private static final String RATE_LIMIT_EXCEEDED_CODE = "RATE_LIMIT_EXCEEDED";

    /** Maximum number of per-IP buckets to keep in memory before evicting the least-recently-used. */
    private static final int MAX_BUCKETS = 50_000;

    private final RateLimitProperties props;
    private final ObjectMapper objectMapper;
    private final Environment environment;

    // Separate caches per tier — bounded LRU map prevents unbounded growth (DoS mitigation).
    private final Map<String, Bucket> authBuckets = Collections.synchronizedMap(
        new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Bucket> eldest) {
                return size() > MAX_BUCKETS;
            }
        }
    );
    private final Map<String, Bucket> browseBuckets = Collections.synchronizedMap(
        new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Bucket> eldest) {
                return size() > MAX_BUCKETS;
            }
        }
    );

    public RateLimitFilter(RateLimitProperties props,
                           ObjectMapper objectMapper,
                           Environment environment) {
        this.props = props;
        this.objectMapper = objectMapper;
        this.environment = environment;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Bypass in the test profile unless force-active is explicitly enabled.
        // force-active=true is set only in RateLimitEnforcementIT to prove AC4 end-to-end
        // without affecting the general test suite.
        boolean testProfile = environment.matchesProfiles("test");
        if (testProfile && !props.isForceActive()) {
            return true;
        }
        if (!props.isEnabled()) {
            return true;
        }
        // Never throttle CORS preflight — doing so would break cross-origin flows.
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String path = request.getRequestURI();
        return !isAuthPath(path) && !isBrowsePath(path);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String ip = request.getRemoteAddr();
        String path = request.getRequestURI();

        Bucket bucket;
        if (isAuthPath(path)) {
            // Explicit synchronization: Collections.synchronizedMap does not make
            // computeIfAbsent atomic — we must synchronize on the map itself so that
            // the LRU removeEldestEntry check and the put are performed under one lock.
            synchronized (authBuckets) {
                bucket = authBuckets.computeIfAbsent(ip,
                    k -> newBucket(props.getAuth().getRequestsPerMinute()));
            }
        } else {
            synchronized (browseBuckets) {
                bucket = browseBuckets.computeIfAbsent(ip,
                    k -> newBucket(props.getBrowse().getRequestsPerMinute()));
            }
        }

        if (bucket.tryConsume(1)) {
            chain.doFilter(request, response);
        } else {
            rejectWithTooManyRequests(request, response);
        }
    }

    private static boolean isAuthPath(String path) {
        return path.startsWith("/api/auth/") || path.equals("/api/auth");
    }

    private static boolean isBrowsePath(String path) {
        return path.startsWith("/api/search/") || path.equals("/api/search")
            || path.startsWith("/api/products/") || path.equals("/api/products");
    }

    private static Bucket newBucket(int requestsPerMinute) {
        Bandwidth limit = Bandwidth.builder()
            .capacity(requestsPerMinute)
            .refillGreedy(requestsPerMinute, Duration.ofMinutes(1))
            .build();
        return Bucket.builder().addLimit(limit).build();
    }

    private void rejectWithTooManyRequests(HttpServletRequest request,
                                           HttpServletResponse response) throws IOException {
        String traceId = MDC.get(RequestIdFilter.MDC_KEY);
        ErrorBody body = ErrorBody.of(
            RATE_LIMIT_EXCEEDED_CODE,
            "요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.",
            request.getRequestURI(),
            traceId != null ? traceId : "");
        ApiResponse<Void> envelope = ApiResponse.failure(body);

        response.setStatus(429);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("Retry-After", "60");
        objectMapper.writeValue(response.getOutputStream(), envelope);
    }
}
