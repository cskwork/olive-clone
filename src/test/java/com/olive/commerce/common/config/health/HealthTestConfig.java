package com.olive.commerce.common.config.health;

import org.springframework.boot.actuate.autoconfigure.health.ConditionalOnEnabledHealthIndicator;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Health check 테스트 구성.
 *
 * <p>테스트 환경에서는 Postgres/Redis/OpenSearch HealthIndicator가
 * 모두 생성되지 않으므로, readiness indicator를 비활성화한다.
 */
@TestConfiguration
public class HealthTestConfig {

    /**
     * 테스트에서는 빈 HealthIndicator로 readiness를 대체한다.
     * main의 HealthConfig와 충돌하지 않도록 다른 빈 이름 사용.
     */
    @Bean("testReadinessHealthIndicator")
    @ConditionalOnEnabledHealthIndicator("readiness")
    public org.springframework.boot.actuate.health.HealthIndicator testReadinessHealthIndicator() {
        return () -> org.springframework.boot.actuate.health.Health.up().build();
    }
}
