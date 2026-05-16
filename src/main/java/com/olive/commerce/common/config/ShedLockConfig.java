package com.olive.commerce.common.config;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * ShedLock 설정 (OLV-080).
 * <p>
 * 멀티 인스턴스 배포 환경에서 스케줄러 중복 실행을 방지합니다.
 * {@code test} 프로필에서는 비활성화하여 테스트 간 영향을 방지합니다.
 */
@Configuration
@Profile("!test")
@EnableSchedulerLock(defaultLockAtMostFor = "55m")
public class ShedLockConfig {

    @Bean
    public LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(
            JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(new JdbcTemplate(dataSource))
                .withTableName("shedlock")
                .build()
        );
    }
}
