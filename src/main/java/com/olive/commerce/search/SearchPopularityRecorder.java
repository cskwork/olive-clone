package com.olive.commerce.search;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * 인기검색어 누적 (OLV-101 / PRD §8.1, wiki §95-search-domain).
 *
 * <p>분 단위 bucket ZSET에 ZINCRBY 1로 누적. key: {@code search:popular:bucket:{epochMinute}},
 * TTL 65분 — {@link SearchPopularityAggregator}가 직전 60 bucket을 합산하므로 충분히 안전.
 *
 * <p>단일 ZSET에 timestamp 멤버를 두는 대안은 ZINCRBY 의미가 깨지고 sweep 비용이 커서 피한다.
 */
@Component
public class SearchPopularityRecorder {

    private static final Logger log = LoggerFactory.getLogger(SearchPopularityRecorder.class);
    static final String BUCKET_KEY_PREFIX = "search:popular:bucket:";
    static final Duration BUCKET_TTL = Duration.ofMinutes(65);

    private final StringRedisTemplate redisTemplate;
    private final Clock clock;

    public SearchPopularityRecorder(StringRedisTemplate redisTemplate, Clock clock) {
        this.redisTemplate = redisTemplate;
        this.clock = clock;
    }

    public void record(String keyword) {
        if (keyword == null) return;
        String trimmed = keyword.trim();
        if (trimmed.isEmpty()) return;

        String key = bucketKey(currentEpochMinute());
        try {
            redisTemplate.opsForZSet().incrementScore(key, trimmed, 1.0);
            redisTemplate.expire(key, BUCKET_TTL);
        } catch (Exception e) {
            // Popular 누적 실패가 검색 결과를 막지는 않는다 — 디그레이드 디자인.
            log.warn("Failed to record popular keyword: {}", trimmed, e);
        }
    }

    long currentEpochMinute() {
        return Instant.now(clock).getEpochSecond() / 60L;
    }

    static String bucketKey(long epochMinute) {
        return BUCKET_KEY_PREFIX + epochMinute;
    }
}
