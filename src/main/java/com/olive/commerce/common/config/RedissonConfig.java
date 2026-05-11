package com.olive.commerce.common.config;

import jakarta.annotation.PreDestroy;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Redisson 분산 락 클라이언트 설정 (OLV-031).
 *
 * <p>Spring Boot의 {@code RedisAutoConfiguration}이 이미 {@code RedisConnectionFactory}를
 * 만들므로, 여기서는 RedissonClient 빈만 추가로 정의한다. Redisson은 내부적으로
 * Lettuce 클라이언트와 호환되는 netty 연결을 사용한다.
 *
 * <p>락 키 패턴: {@code lock:inv:{product_option_id}} (InventoryService 사용).
 *
 * <p>Redis 다운 시 fallback은 {@link InventoryLockProperties#fallbackToDb} feature flag로
 * 제어 — 도메인 서비스가 분기한다.
 */
@Configuration
class RedissonConfig {

    private static final Logger log = LoggerFactory.getLogger(RedissonConfig.class);

    private final RedisProperties redisProperties;
    private final InventoryLockProperties lockProperties;

    private RedissonClient redissonClient;

    RedissonConfig(RedisProperties redisProperties, InventoryLockProperties lockProperties) {
        this.redisProperties = redisProperties;
        this.lockProperties = lockProperties;
    }

    @Bean(destroyMethod = "shutdown")
    RedissonClient redissonClient() {
        String address = "redis://" + redisProperties.getHost() + ":" + redisProperties.getPort();

        Config config = new Config();
        SingleServerConfig serverConfig = config.useSingleServer()
                .setAddress(address)
                .setConnectionPoolSize(10)
                .setConnectionMinimumIdleSize(2)
                .setIdleConnectionTimeout(10000)
                .setConnectTimeout(10000)
                .setTimeout(3000)
                .setRetryAttempts(2)
                .setRetryInterval(1500);

        // Redis 비밀번호가 설정된 경우 (LocalStack 등 개발 환경에서는 없을 수 있음)
        if (redisProperties.getPassword() != null && !redisProperties.getPassword().isEmpty()) {
            serverConfig.setPassword(redisProperties.getPassword());
        }

        this.redissonClient = Redisson.create(config);

        log.info("Redisson distributed lock client initialized (address={}, lockWaitTime={}s, lockLeaseTime={}s, fallbackToDb={})",
                address,
                lockProperties.getLockWaitTimeSeconds(),
                lockProperties.getLockLeaseTimeSeconds(),
                lockProperties.isFallbackToDb());

        return redissonClient;
    }

    @PreDestroy
    void verifyShutdown() {
        if (redissonClient != null && !redissonClient.isShutdown()) {
            log.info("RedissonClient shutting down...");
            // @Bean(destroyMethod)으로 자동 호출되지만, 명시적 로그를 위해 남김
        }
    }
}
