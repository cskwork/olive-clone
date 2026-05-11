package com.olive.commerce.common.config.health;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.stereotype.Component;

/**
 * Redis health indicator.
 * <p>PING 명령을 실행하여 Redis 연결을 검증. Redis 아웃티지는 DEGRADED로 간주해
 * readiness만 DOWN, liveness는 UP 유지.</p>
 */
@Component
public class RedisHealthIndicator implements HealthIndicator {

    private final RedisConnectionFactory connectionFactory;

    public RedisHealthIndicator(RedisConnectionFactory connectionFactory) {
        this.connectionFactory = connectionFactory;
    }

    @Override
    public Health health() {
        try {
            var connection = connectionFactory.getConnection();
            String pong = connection.ping();
            connection.close();
            if ("PONG".equals(pong)) {
                return Health.up()
                    .withDetail("redis", "connected")
                    .build();
            }
            return Health.down()
                .withDetail("redis", "unexpected response")
                .withDetail("response", pong)
                .build();
        } catch (Exception e) {
            return Health.down()
                .withDetail("redis", "disconnected")
                .withDetail("error", e.getClass().getSimpleName())
                .withDetail("message", e.getMessage())
                .build();
        }
    }
}
