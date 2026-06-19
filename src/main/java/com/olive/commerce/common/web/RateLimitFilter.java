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
import org.springframework.core.env.Profiles;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-IP token-bucket rate limiter applied to high-traffic public endpoints.
 *
 * <p>Two limit tiers are supported:
 * <ul>
 *   <li><b>auth</b>  — /api/auth/**   (strict, prevents credential-stuffing)</li>
 *   <li><b>browse</b>— /api/search/** and /api/products/** (lenient crawl guard)</li>
 * </ul>
 *
 * <p><b>Profile gating</b>: the limiter is unconditionally bypassed when the
 * {@code test} Spring profile is active, regardless of {@code olive.ratelimit.enabled}.
 * This prevents the existing test suite (which fires many requests per IP from a single
 * loopback address) from being accidentally throttled without needing YAML changes.
 *
 * <p><b>IP resolution</b>: uses {@link HttpServletRequest#getRemoteAddr()} only.
 * X-Forwarded-For is intentionally ignored because it is trivially spoofable.
 * Configure X-Forwarded-For handling at the proxy level rather than trusting headers.
 *
 * <p>The limiter is also disabled when {@code olive.ratelimit.enabled=false}.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private static final String RATE_LIMIT_EXCEEDED_CODE = "RATE_LIMIT_EXCEEDED";

    private final RateLimitProperties props;
    private final ObjectMapper objectMapper;
    private final Environment environment;

    // Separate caches per tier to allow independent per-IP token pools.
    private final ConcurrentHashMap<String, Bucket> authBuckets = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Bucket> browseBuckets = new ConcurrentHashMap<>();

    public RateLimitFilter(RateLimitProperties props,
                           ObjectMapper objectMapper,
                           Environment environment) {
        this.props = props;
        this.objectMapper = objectMapper;
        this.environment = environment;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Always bypass in the test profile — no YAML override needed.
        if (environment.matchesProfiles("test")) {
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
            bucket = authBuckets.computeIfAbsent(ip,
                k -> newBucket(props.getAuth().getRequestsPerMinute()));
        } else {
            bucket = browseBuckets.computeIfAbsent(ip,
                k -> newBucket(props.getBrowse().getRequestsPerMinute()));
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
