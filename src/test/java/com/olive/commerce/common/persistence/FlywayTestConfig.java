package com.olive.commerce.common.persistence;

import org.flywaydb.core.Flyway;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * Flyway 테스트 구성.
 *
 * <p>{@link @DataJpaTest}는 기본적으로 Flyway를 실행하지 않습니다.
 * 이 구성을 통해 Testcontainers로 시작한 Postgres DB에 마이그레이션을 수동으로 실행합니다.
 */
@Configuration
public class FlywayTestConfig {

    @Bean
    public Flyway flyway(DataSource dataSource) {
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load();

        // 테스트용 DB 마이그레이션 실행
        // Testcontainers는 매번 새로운 컨테이너를 사용하므로 clean()은 불필요
        flyway.migrate();

        return flyway;
    }
}
