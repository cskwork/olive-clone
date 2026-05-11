package com.olive.commerce.common.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Redis 통합 진입점. Spring Boot의 RedisAutoConfiguration이 이미
 * {@code StringRedisTemplate}(== {@code RedisTemplate<String,String>} 서브클래스)을
 * 등록하므로 별도 빈 정의 없이 부팅 시 한 줄 로그만 남긴다.
 *
 * 도메인 코드는 그대로 {@code @Autowired RedisTemplate<String,String>} 으로 받을 수 있다.
 */
@Configuration
public class RedisConfig {

    private static final Logger log = LoggerFactory.getLogger(RedisConfig.class);

    private final RedisProperties properties;

    public RedisConfig(RedisProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    void announce() {
        log.info("Redis client initialized (host={}, port={})", properties.getHost(), properties.getPort());
    }
}
