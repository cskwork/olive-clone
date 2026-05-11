package com.olive.commerce.search;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.zset.Aggregate;
import org.springframework.data.redis.connection.zset.Weights;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 직전 60분 bucket을 1분 주기로 합산해 {@link #CURRENT_KEY} ZSET에 노출.
 *
 * <p>이 ZSET을 컨트롤러가 ZREVRANGE로 읽어 인기검색어를 반환한다 — 사용자 응답 경로는
 * 단일 명령어로 끝나 빠르다.
 *
 * <p>{@link SchedulingConfig}가 {@code @Profile("!test")}이므로 본 {@code @Scheduled}는
 * 프로덕션에서만 활성. 테스트는 {@link #aggregateOnce()}를 직접 호출해 결정론적 검증.
 */
@Component
public class SearchPopularityAggregator {

    private static final Logger log = LoggerFactory.getLogger(SearchPopularityAggregator.class);

    static final String CURRENT_KEY = "search:popular:current";
    static final int WINDOW_MINUTES = 60;
    static final Duration CURRENT_TTL = Duration.ofMinutes(2);

    private final StringRedisTemplate redisTemplate;
    private final SearchPopularityRecorder recorder;

    public SearchPopularityAggregator(StringRedisTemplate redisTemplate, SearchPopularityRecorder recorder) {
        this.redisTemplate = redisTemplate;
        this.recorder = recorder;
    }

    @Scheduled(fixedDelay = 60_000L)
    public void aggregate() {
        try {
            aggregateOnce();
        } catch (Exception e) {
            log.warn("Popular keyword aggregation failed (will retry next tick)", e);
        }
    }

    public long aggregateOnce() {
        long now = recorder.currentEpochMinute();
        List<String> keys = new ArrayList<>(WINDOW_MINUTES);
        for (int i = 0; i < WINDOW_MINUTES; i++) {
            keys.add(SearchPopularityRecorder.bucketKey(now - i));
        }
        // ZUNIONSTORE는 누락 키가 있어도 빈 ZSET처럼 취급되므로 사전 존재 체크 불필요.
        ZSetOperations<String, String> z = redisTemplate.opsForZSet();
        Long count = z.unionAndStore(
            keys.get(0),
            keys.subList(1, keys.size()),
            CURRENT_KEY,
            Aggregate.SUM,
            Weights.fromSetCount(keys.size())
        );
        redisTemplate.expire(CURRENT_KEY, CURRENT_TTL);
        return count != null ? count : 0L;
    }

    public List<SearchDtos.PopularKeyword> readTop(int size) {
        int capped = Math.min(Math.max(size, 1), 100);
        ZSetOperations<String, String> z = redisTemplate.opsForZSet();
        Set<ZSetOperations.TypedTuple<String>> tuples =
            z.reverseRangeWithScores(CURRENT_KEY, 0, capped - 1L);
        if (tuples == null || tuples.isEmpty()) return List.of();

        // 결정론적 순서 보장.
        Set<ZSetOperations.TypedTuple<String>> ordered = new LinkedHashSet<>(tuples);
        List<SearchDtos.PopularKeyword> out = new ArrayList<>(ordered.size());
        for (ZSetOperations.TypedTuple<String> t : ordered) {
            String kw = t.getValue();
            Double score = t.getScore();
            if (kw == null || score == null) continue;
            out.add(new SearchDtos.PopularKeyword(kw, score.longValue()));
        }
        Collections.sort(out, (a, b) -> {
            int byCount = Long.compare(b.count(), a.count());
            return byCount != 0 ? byCount : a.keyword().compareTo(b.keyword());
        });
        return out;
    }
}
