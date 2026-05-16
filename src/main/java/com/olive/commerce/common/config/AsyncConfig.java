package com.olive.commerce.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * {@code @Async} 메서드 실행을 활성화합니다.
 * <p>
 * {@code test} 프로필에서는 비활성화하여 테스트 간 영향을 방지합니다.
 */
@Configuration
@Profile("!test")
@EnableAsync
public class AsyncConfig {
}
