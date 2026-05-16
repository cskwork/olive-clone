package com.olive.commerce.common.persistence;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.TestExecutionListeners;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * JVM-싱글톤 Postgres 컨테이너.
 *
 * <p>JUnit {@code @Testcontainers}/{@code @Container} 의 per-class lifecycle 은
 * 같은 부모를 공유하는 두 번째 {@code @DataJpaTest} 가 등장하면 깨진다 —
 * Spring 의 컨텍스트 캐시는 동일 설정을 재사용하지만, 첫 클래스 종료 시점에
 * 컨테이너가 stop 되어 캐시된 DataSource 가 죽은 포트를 가리키게 된다.
 *
 * <p>그래서 컨테이너는 정적 초기화 블록에서 단 한 번 {@code start()} 하고
 * JVM 종료 시 Ryuk reaper 가 정리한다 — Testcontainers 공식 권장 패턴.
 */
@ActiveProfiles("test")
@Import(FlywayTestConfig.class)
@TestExecutionListeners(
    listeners = FlywayResetTestExecutionListener.class,
    mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS
)
@TestPropertySource(properties = {
    "spring.jpa.hibernate.ddl-auto=none",
    "spring.flyway.clean-disabled=false"
})
public abstract class PostgresIntegrationSupport {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("commerce")
            .withUsername("commerce")
            .withPassword("commerce");

    static final GenericContainer<?> REDIS =
        new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    static {
        POSTGRES.start();
        REDIS.start();
    }

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }
}
