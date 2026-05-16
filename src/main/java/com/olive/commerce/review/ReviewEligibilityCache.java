package com.olive.commerce.review;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;

/**
 * 리뷰 작성 가능 여부 캐시 서비스.
 * <p>Redis를 사용하여 배송 완료된 주문의 리뷰 작성 가능 여부를 빠르게 조회합니다.
 * OLV-090의 eligibility check fast-path에서 사용됩니다.
 */
@Service
public class ReviewEligibilityCache {

    private static final Logger log = LoggerFactory.getLogger(ReviewEligibilityCache.class);
    private static final String ELIGIBLE_PREFIX = "review:eligible:";
    private static final Duration CACHE_TTL = Duration.ofDays(90); // 배송 완료 후 90일

    private final RedisTemplate<String, String> redisTemplate;

    public ReviewEligibilityCache(RedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 주문의 상품들을 리뷰 작성 가능으로 마크합니다.
     *
     * @param orderId 주문 ID
     */
    public void markEligible(Long orderId) {
        String key = ELIGIBLE_PREFIX + orderId;
        redisTemplate.opsForValue().set(key, "true", CACHE_TTL);
        log.debug("Review eligibility marked: orderId={}", orderId);
    }

    /**
     * 주문이 리뷰 작성 가능한지 확인합니다.
     *
     * @param orderId 주문 ID
     * @return 리뷰 작성 가능 여부
     */
    public boolean isEligible(Long orderId) {
        String key = ELIGIBLE_PREFIX + orderId;
        Boolean exists = redisTemplate.hasKey(key);
        return Boolean.TRUE.equals(exists);
    }

    /**
     * 리뷰 작성 가능한 주문 ID 목록을 조회합니다.
     *
     * @param memberId 회원 ID
     * @return 주문 ID 목록
     */
    public Set<String> getEligibleOrderIdsForMember(Long memberId) {
        String pattern = ELIGIBLE_PREFIX + "*";
        Set<String> keys = redisTemplate.keys(pattern);
        return keys;
    }

    /**
     * 주문의 리뷰 작성 가능 마크를 제거합니다 (리뷰 작성 후).
     *
     * @param orderId 주문 ID
     */
    public void clearEligibility(Long orderId) {
        String key = ELIGIBLE_PREFIX + orderId;
        redisTemplate.delete(key);
        log.debug("Review eligibility cleared: orderId={}", orderId);
    }
}
