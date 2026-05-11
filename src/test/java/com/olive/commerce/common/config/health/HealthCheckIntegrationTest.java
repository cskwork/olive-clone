package com.olive.commerce.common.config.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;

/**
 * OLV-131 health-check contract tests.
 *
 * Endpoint exposure is Spring Boot actuator behavior; these tests keep the app
 * contract focused on the custom readiness aggregation and batch DLQ indicator.
 */
class HealthCheckIntegrationTest {

    @Test
    void readinessHealth_returnsUp_whenDependenciesAreUp() {
        HealthIndicator readiness = readinessIndicator(
            Health.up().build(),
            Health.up().build(),
            Health.up().build()
        );

        Health health = readiness.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void readinessHealth_returnsDown_whenAnyDependencyIsDown() {
        HealthIndicator readiness = readinessIndicator(
            Health.up().build(),
            Health.down().withDetail("redis", "disconnected").build(),
            Health.up().build()
        );

        Health health = readiness.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails())
            .containsEntry("failedIndicator", RedisHealthIndicator.class.getSimpleName());
    }

    @Test
    void batchHealth_returnsUp_withNoDlqEntries() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement("SELECT COUNT(*) FROM outbox_events WHERE dlq = TRUE"))
            .thenReturn(statement);
        when(statement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getInt(1)).thenReturn(0);

        Health health = new BatchHealthIndicator(dataSource).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("dlqCount", 0);
    }

    @Test
    void batchHealth_returnsDegraded_whenDlqEntriesExist() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement("SELECT COUNT(*) FROM outbox_events WHERE dlq = TRUE"))
            .thenReturn(statement);
        when(statement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getInt(1)).thenReturn(3);

        Health health = new BatchHealthIndicator(dataSource).health();

        assertThat(health.getStatus().getCode()).isEqualTo("DEGRADED");
        assertThat(health.getDetails()).containsEntry("dlqCount", 3);
    }

    private HealthIndicator readinessIndicator(Health postgres, Health redis, Health openSearch) {
        PostgresHealthIndicator postgresHealth = mock(PostgresHealthIndicator.class);
        RedisHealthIndicator redisHealth = mock(RedisHealthIndicator.class);
        OpenSearchHealthIndicator openSearchHealth = mock(OpenSearchHealthIndicator.class);
        when(postgresHealth.health()).thenReturn(postgres);
        when(redisHealth.health()).thenReturn(redis);
        when(openSearchHealth.health()).thenReturn(openSearch);
        return new HealthConfig().readinessHealthIndicator(
            postgresHealth,
            redisHealth,
            openSearchHealth
        );
    }
}
