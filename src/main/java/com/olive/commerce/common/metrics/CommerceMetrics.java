package com.olive.commerce.common.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.Tags;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 커머스 도메인 전용 메트릭 (OLV-130 / PRD §16.1).
 *
 * <p>모든 메트릭은 {@code /actuator/prometheus}에서 Prometheus 형식으로 노출된다.
 *
 * <p>메트릭 목록:
 * <ul>
 *   <li>{@code commerce_orders_created_total} — 주문 생성 수 (tag: status)</li>
 *   <li>{@code commerce_payments_total} — 결제 시도 수 (tag: status, pg_provider)</li>
 *   <li>{@code commerce_inventory_reservation_failures_total} — 재고 예약 실패 수 (tag: optionId)</li>
 *   <li>{@code commerce_orders_canceled_total} — 주문 취소 수</li>
 *   <li>{@code commerce_search_request_seconds} — 검색 요청 소요 시간 (timer)</li>
 *   <li>{@code commerce_search_empty_result_total} — 빈 검색 결과 수</li>
 *   <li>{@code cache_redis_hit_ratio} — Redis 캐시 적중률 (gauge, 0~1)</li>
 *   <li>{@code db_pool_active} — DB 커넥션 풀 활성 연결 수 (gauge)</li>
 *   <li>{@code outbox_pending_count} — 처리 대기 중인 outbox 이벤트 수 (gauge)</li>
 * </ul>
 */
@Component
public class CommerceMetrics {

    private final MeterRegistry registry;
    private final Counter ordersCanceledCounter;
    private final Counter searchEmptyResultCounter;
    private final AtomicLong redisHits = new AtomicLong(0);
    private final AtomicLong redisMisses = new AtomicLong(0);
    private final AtomicLong outboxPendingCount = new AtomicLong(0);
    private final AtomicLong outboxDlqCount = new AtomicLong(0);

    public CommerceMetrics(MeterRegistry registry) {
        this.registry = registry;

        // 고정 태그가 있는 카운터들은 생성 시점에 등록
        this.ordersCanceledCounter = Counter.builder("commerce_orders_canceled_total")
                .description("Total number of orders canceled")
                .register(registry);

        this.searchEmptyResultCounter = Counter.builder("commerce_search_empty_result_total")
                .description("Total number of empty search results")
                .register(registry);

        // Redis 캐시 적중률 게이지 (0~1 사이 값)
        registry.gauge("cache_redis_hit_ratio", this, commerceMetrics -> {
            long hits = redisHits.get();
            long misses = redisMisses.get();
            long total = hits + misses;
            return total > 0 ? (double) hits / total : 0.0;
        });

        // Outbox 대기 수 게이지
        registry.gauge("outbox_pending_count", outboxPendingCount);

        // Outbox DLQ 수 게이지 — alert when > 0
        registry.gauge("outbox_dlq_count", outboxDlqCount);

        // DB 풀 활성 연결 수는 HikariCP가 자동으로 노출함
        // spring.datasource.hikari.maximum-pool-size 설정으로 자동 생성됨
    }

    // Orders
    public void orderCreated(String status) {
        registry.counter("commerce_orders_created_total",
                Tags.of("status", status)).increment();
    }

    public void orderCanceled() {
        ordersCanceledCounter.increment();
    }

    // Payments
    public void paymentAttempted(String status, String pgProvider) {
        registry.counter("commerce_payments_total",
                Tags.of("status", status, "pg_provider", pgProvider)).increment();
    }

    // Inventory
    public void inventoryReservationFailure(Long optionId) {
        registry.counter("commerce_inventory_reservation_failures_total",
                Tags.of("optionId", String.valueOf(optionId))).increment();
    }

    // Search
    public Timer.Sample startSearchTimer() {
        return Timer.start(registry);
    }

    public void recordSearch(Timer.Sample sample) {
        sample.stop(Timer.builder("commerce_search_request_seconds")
                .description("Search request duration")
                .register(registry));
    }

    public void searchEmptyResult() {
        searchEmptyResultCounter.increment();
    }

    // Cache
    public void recordCacheHit() {
        redisHits.incrementAndGet();
    }

    public void recordCacheMiss() {
        redisMisses.incrementAndGet();
    }

    // Outbox
    public void setOutboxPendingCount(long count) {
        outboxPendingCount.set(count);
    }

    public void setOutboxDlqCount(long count) {
        outboxDlqCount.set(count);
    }

    public long getOutboxDlqCount() {
        return outboxDlqCount.get();
    }

    // MeterRegistry 접근자 (외부에서 직접 사용 필요 시)
    public MeterRegistry getRegistry() {
        return registry;
    }
}
