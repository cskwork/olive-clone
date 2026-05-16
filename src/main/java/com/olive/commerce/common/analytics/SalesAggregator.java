package com.olive.commerce.common.analytics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.TimeUnit;

/**
 * 매출 집계 서비스.
 * <p>Redis를 사용하여 당일 매출을 실시간으로 집계합니다.
 * 전체 일별 롤업은 OLV-120 배치에서 처리합니다.
 */
@Service
public class SalesAggregator {

    private static final Logger log = LoggerFactory.getLogger(SalesAggregator.class);
    private static final String SALES_KEY_PREFIX = "sales:daily:";
    private static final long CACHE_TTL_DAYS = 37; // 1달 + 여분

    private final RedisTemplate<String, String> redisTemplate;

    public SalesAggregator(RedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 주문 매출을 기록합니다.
     *
     * @param orderId 주문 ID
     * @param amount  결제 금액
     */
    public void recordSale(Long orderId, BigDecimal amount) {
        String dateKey = getDateKey();
        String key = SALES_KEY_PREFIX + dateKey;

        redisTemplate.opsForValue().increment(key, amount.longValue());
        redisTemplate.expire(key, CACHE_TTL_DAYS, TimeUnit.DAYS);

        log.debug("Sale recorded: date={}, orderId={}, amount={}", dateKey, orderId, amount);
    }

    /**
     * 주문 취소/환불로 인한 매출 차감을 기록합니다.
     *
     * @param orderId 주문 ID
     * @param amount  차감 금액
     */
    public void recordReversal(Long orderId, BigDecimal amount) {
        String dateKey = getDateKey();
        String key = SALES_KEY_PREFIX + dateKey;

        redisTemplate.opsForValue().decrement(key, amount.longValue());

        log.debug("Sale reversal recorded: date={}, orderId={}, amount={}", dateKey, orderId, amount);
    }

    /**
     * 특정 날짜의 매출 합계를 조회합니다.
     *
     * @param date 조회 대상 날짜
     * @return 매출 합계
     */
    public BigDecimal getDailySales(LocalDate date) {
        String key = SALES_KEY_PREFIX + date.format(DateTimeFormatter.ISO_DATE);
        String value = redisTemplate.opsForValue().get(key);
        return value != null ? new BigDecimal(value) : BigDecimal.ZERO;
    }

    /**
     * 오늘의 매출 합계를 조회합니다.
     *
     * @return 오늘의 매출 합계
     */
    public BigDecimal getTodaySales() {
        return getDailySales(LocalDate.now());
    }

    private String getDateKey() {
        return LocalDate.now().format(DateTimeFormatter.ISO_DATE);
    }
}
