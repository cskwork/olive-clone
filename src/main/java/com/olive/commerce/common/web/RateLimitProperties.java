package com.olive.commerce.common.web;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Rate-limit configuration bound to {@code olive.ratelimit.*}.
 *
 * <p>Field-default values are intentionally generous so that the normal test
 * suite (which may fire many requests per IP) is not accidentally throttled.
 * Set {@code olive.ratelimit.enabled=false} in application-test.yml to disable
 * the limiter entirely in the test profile.
 *
 * <pre>
 * olive:
 *   ratelimit:
 *     enabled: true
 *     auth:
 *       requests-per-minute: 20
 *     browse:
 *       requests-per-minute: 300
 * </pre>
 */
@ConfigurationProperties(prefix = "olive.ratelimit")
public class RateLimitProperties {

    /** Whether the rate limiter is active. Disable in application-test.yml. */
    private boolean enabled = true;

    private Auth auth = new Auth();
    private Browse browse = new Browse();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Auth getAuth() {
        return auth;
    }

    public void setAuth(Auth auth) {
        this.auth = auth;
    }

    public Browse getBrowse() {
        return browse;
    }

    public void setBrowse(Browse browse) {
        this.browse = browse;
    }

    public static class Auth {
        /** Maximum requests allowed per minute to /api/auth/**. Default: 20. */
        private int requestsPerMinute = 20;

        public int getRequestsPerMinute() {
            return requestsPerMinute;
        }

        public void setRequestsPerMinute(int requestsPerMinute) {
            this.requestsPerMinute = requestsPerMinute;
        }
    }

    public static class Browse {
        /** Maximum requests allowed per minute to /api/search/** and /api/products/**. Default: 300. */
        private int requestsPerMinute = 300;

        public int getRequestsPerMinute() {
            return requestsPerMinute;
        }

        public void setRequestsPerMinute(int requestsPerMinute) {
            this.requestsPerMinute = requestsPerMinute;
        }
    }
}
