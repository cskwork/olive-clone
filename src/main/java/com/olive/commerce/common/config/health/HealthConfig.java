package com.olive.commerce.common.config.health;

import org.springframework.boot.actuate.autoconfigure.health.ConditionalOnEnabledHealthIndicator;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Health check 구성.
 * <p>liveness는 별도 그룹(/actuator/health/liveness)으로 - 프로세스存活만 확인.</p>
 * <p>readiness는 별도 그룹(/actuator/health/readiness)으로 - PG/Redis/OpenSearch
 * 모두 UP이어야 TRAFFIC-READY.</p>
 *
 * @see <a href="https://docs.spring.io/spring-boot/docs/current/reference/html/actuator.html#actuator.endpoints.health.groups">Health Groups</a>
 */
@Configuration
public class HealthConfig {

    /**
     * Readiness 그룹: Postgres/Redis/OpenSearch의 HealthIndicator를 모두 포함.
     * 하나라도 DOWN이면 readiness는 503.
     *
     * Spring Boot의 `management.health.readinessstate.enabled=true`와 별개로,
     * 본 앱은 인프라 하위 시스템 검증을 위한 custom readiness를 사용한다.
     */
    @Bean
    @ConditionalOnEnabledHealthIndicator("readiness")
    public HealthIndicator readinessHealthIndicator(
            PostgresHealthIndicator postgresHealth,
            RedisHealthIndicator redisHealth,
            OpenSearchHealthIndicator openSearchHealth) {
        return new CompositeHealthIndicator(postgresHealth, redisHealth, openSearchHealth);
    }

    /**
     * 간단한 Composite HealthIndicator.
     * 모든 하위 indicator가 UP이면 UP, 하나라도 DOWN이면 DOWN.
     */
    private static class CompositeHealthIndicator implements HealthIndicator {
        private final HealthIndicator[] indicators;

        CompositeHealthIndicator(HealthIndicator... indicators) {
            this.indicators = indicators;
        }

        @Override
        public org.springframework.boot.actuate.health.Health health() {
            var builder = org.springframework.boot.actuate.health.Health.up();
            for (HealthIndicator indicator : indicators) {
                var health = indicator.health();
                if (!health.getStatus().getCode().equals(org.springframework.boot.actuate.health.Status.UP.getCode())) {
                    return org.springframework.boot.actuate.health.Health.down()
                        .withDetail("reason", "One or more dependencies are down")
                        .withDetail("failedIndicator", indicator.getClass().getSimpleName())
                        .build();
                }
            }
            return builder.build();
        }
    }
}
