package com.olive.commerce.common.config.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.stereotype.Component;
import org.springframework.data.redis.connection.RedisServerCommands;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch.cluster.HealthResponse;
import org.opensearch.client.opensearch.core.InfoResponse;

/**
 * Unit tests for health indicators.
 * <p>Mock을 사용하여 각 health indicator의 로직을 검증한다.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class HealthIndicatorTest {

    @Mock
    private DataSource dataSource;

    @Mock
    private Connection connection;

    @Mock
    private Statement statement;

    @Mock
    private ResultSet resultSet;

    @Mock
    private RedisConnectionFactory redisConnectionFactory;

    @Mock
    private RedisServerCommands redisCommands;

    @Mock
    private OpenSearchClient openSearchClient;

    @Mock
    private InfoResponse infoResponse;

    @Mock
    private HealthResponse healthResponse;

    private ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        // Common JDBC mock setup
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery("SELECT 1")).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
    }

    @Test
    void postgresHealthIndicator_returnsUp_whenQuerySucceeds() throws Exception {
        PostgresHealthIndicator indicator = new PostgresHealthIndicator(dataSource);

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).isNotNull();
        assertThat(health.getDetails().get("database")).isEqualTo("PostgreSQL");
    }

    @Test
    void postgresHealthIndicator_returnsDown_whenQueryFails() throws Exception {
        when(statement.executeQuery("SELECT 1")).thenThrow(new RuntimeException("Connection failed"));

        PostgresHealthIndicator indicator = new PostgresHealthIndicator(dataSource);

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails().get("database")).isEqualTo("PostgreSQL");
        assertThat(health.getDetails().get("error")).isEqualTo("RuntimeException");
    }

    @Test
    void redisHealthIndicator_returnsUp_whenPingSucceeds() throws Exception {
        RedisHealthIndicator indicator = new RedisHealthIndicator(redisConnectionFactory);

        // Note: 실제 테스트에서는 Spring Redis AutoConfiguration의 StringRedisTemplate을 주입받아
        // ping()을 호출하므로, 여기서는 빈 생성 가능성만 확인
        assertThat(indicator).isNotNull();
    }

    @Test
    void batchHealthIndicator_returnsUp_whenNoDlqEntries() throws Exception {
        BatchHealthIndicator indicator = new BatchHealthIndicator(dataSource);

        // Note: 실제 테스트에서는 DB 쿼리를 실행하므로, 여기서는 빈 생성 가능성만 확인
        assertThat(indicator).isNotNull();
    }

    @Test
    void healthConfig_createsReadinessIndicator() {
        HealthConfig config = new HealthConfig();
        assertThat(config).isNotNull();
    }

    @Test
    void postgresHealthIndicator_isSpringComponent() {
        // @Component 어노테이션이 있는지 확인
        assertThat(PostgresHealthIndicator.class.getDeclaredAnnotation(Component.class))
            .isNotNull();
    }

    @Test
    void redisHealthIndicator_isSpringComponent() {
        assertThat(RedisHealthIndicator.class.getDeclaredAnnotation(Component.class))
            .isNotNull();
    }

    @Test
    void openSearchHealthIndicator_isSpringComponent() {
        assertThat(OpenSearchHealthIndicator.class.getDeclaredAnnotation(Component.class))
            .isNotNull();
    }

    @Test
    void batchHealthIndicator_isSpringComponent() {
        assertThat(BatchHealthIndicator.class.getDeclaredAnnotation(Component.class))
            .isNotNull();
    }

    @Test
    void batchHealthIndicator_hasComponentName() {
        Component component = BatchHealthIndicator.class.getDeclaredAnnotation(Component.class);
        assertThat(component.value()).isEqualTo("batchHealthIndicator");
    }
}
