package com.olive.commerce.common.config.health;

import javax.sql.DataSource;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.stereotype.Component;

/**
 * Batch health indicator.
 * <p>outbox_events 테이블의 dlq=true 레코드 수를 보고. DLQ가 존재하면 DEGRADED
 * 상태를 반환하여 운영자가 수동 재처리가 필요함을 알린다.</p>
 */
@Component("batchHealthIndicator")
public class BatchHealthIndicator implements HealthIndicator {

    private final DataSource dataSource;

    public BatchHealthIndicator(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public Health health() {
        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement(
                 "SELECT COUNT(*) FROM outbox_events WHERE dlq = TRUE")) {
            var rs = stmt.executeQuery();
            if (rs.next()) {
                int dlqCount = rs.getInt(1);
                if (dlqCount > 0) {
                    return Health.status(new Status("DEGRADED"))
                        .withDetail("dlqCount", dlqCount)
                        .withDetail("message", "Dead-letter queue entries require manual recovery")
                        .build();
                }
                return Health.up()
                    .withDetail("dlqCount", 0)
                    .withDetail("message", "No dead-letter entries")
                    .build();
            }
            return Health.unknown()
                .withDetail("reason", "Query returned no rows")
                .build();
        } catch (Exception e) {
            return Health.down()
                .withDetail("error", e.getClass().getSimpleName())
                .withDetail("message", e.getMessage())
                .build();
        }
    }
}
