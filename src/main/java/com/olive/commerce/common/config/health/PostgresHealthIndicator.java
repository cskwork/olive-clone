package com.olive.commerce.common.config.health;

import javax.sql.DataSource;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * PostgreSQL health indicator.
 * <p>SELECT 1을 실행하여 DB 연결을 검증. PG 아웃티지는 TOTAL 장애로 간주해
 * liveness와 readiness 모두 DOWN.</p>
 */
@Component
public class PostgresHealthIndicator implements HealthIndicator {

    private final DataSource dataSource;

    public PostgresHealthIndicator(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public Health health() {
        try (var conn = dataSource.getConnection();
             var stmt = conn.createStatement();
             var rs = stmt.executeQuery("SELECT 1")) {
            if (rs.next()) {
                return Health.up()
                    .withDetail("database", "PostgreSQL")
                    .build();
            }
            return Health.down()
                .withDetail("database", "PostgreSQL")
                .withDetail("reason", "SELECT 1 returned no rows")
                .build();
        } catch (Exception e) {
            return Health.down()
                .withDetail("database", "PostgreSQL")
                .withDetail("error", e.getClass().getSimpleName())
                .withDetail("message", e.getMessage())
                .build();
        }
    }
}
