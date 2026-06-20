package com.olive.commerce.admin;

import com.olive.commerce.common.api.ApiResponse;
import com.olive.commerce.common.metrics.CommerceMetrics;
import com.olive.commerce.search.OutboxEvent;
import com.olive.commerce.search.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Outbox DLQ 관리자 API.
 * <p>경로: {@code /api/admin/outbox}
 * <p>권한: {@code SUPER_ADMIN}
 *
 * <p>DLQ(Dead-Letter Queue)에 있는 outbox 이벤트를 조회하고 재처리 대기열(PENDING)로
 * 되돌리는 운영 엔드포인트입니다. SecurityConfig의 {@code /api/admin/**} 규칙에 의해
 * 이미 SUPER_ADMIN 역할 제한이 적용됩니다 — 여기서는 추가로 @PreAuthorize로 명시합니다.
 */
@RestController
@RequestMapping("/api/admin/outbox")
@RequiredArgsConstructor
public class OutboxAdminController {

    private static final Logger log = LoggerFactory.getLogger(OutboxAdminController.class);

    private final OutboxEventRepository outboxEventRepository;
    private final CommerceMetrics commerceMetrics;

    /**
     * DLQ에 있는 모든 outbox 이벤트를 조회합니다.
     *
     * @return DLQ 이벤트 목록 및 총 건수
     */
    @GetMapping("/dlq")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<DlqListResponse>> listDlq() {
        List<OutboxEvent> events = outboxEventRepository.findAllDlq();
        long count = events.size();
        // Refresh gauge each time the admin lists DLQ entries.
        commerceMetrics.setOutboxDlqCount(count);
        return ResponseEntity.ok(ApiResponse.success(DlqListResponse.of(events, count)));
    }

    /**
     * 특정 DLQ 이벤트를 PENDING으로 재처리 대기열에 등록합니다.
     * attempt_count와 dlq 플래그를 초기화하여 드레이너가 재시도하도록 합니다.
     *
     * @param id 재처리할 outbox 이벤트 ID
     * @return 재처리 결과
     */
    @PostMapping("/dlq/{id}/requeue")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Transactional
    public ResponseEntity<ApiResponse<RequeueResponse>> requeueById(@PathVariable Long id) {
        OutboxEvent event = outboxEventRepository.findById(id)
            .orElse(null);
        if (event == null || !event.isDlq()) {
            log.warn("Requeue requested for event not in DLQ: id={}", id);
            return ResponseEntity.ok(ApiResponse.success(
                new RequeueResponse(0, "Event not found in DLQ")));
        }
        event.requeueFromDlq();
        outboxEventRepository.save(event);
        long remaining = outboxEventRepository.countByDlqTrue();
        commerceMetrics.setOutboxDlqCount(remaining);
        log.info("Outbox DLQ event requeued: id={} remainingDlq={}", id, remaining);
        return ResponseEntity.ok(ApiResponse.success(new RequeueResponse(1, "Requeued 1 event")));
    }

    /**
     * DLQ에 있는 모든 이벤트를 PENDING으로 재처리 대기열에 등록합니다.
     *
     * @return 재처리된 이벤트 수
     */
    @PostMapping("/dlq/requeue-all")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Transactional
    public ResponseEntity<ApiResponse<RequeueResponse>> requeueAll() {
        List<OutboxEvent> dlqEvents = outboxEventRepository.findAllDlq();
        for (OutboxEvent event : dlqEvents) {
            event.requeueFromDlq();
        }
        outboxEventRepository.saveAll(dlqEvents);
        commerceMetrics.setOutboxDlqCount(0);
        log.info("Outbox DLQ events requeued: count={}", dlqEvents.size());
        return ResponseEntity.ok(ApiResponse.success(
            new RequeueResponse(dlqEvents.size(), "Requeued " + dlqEvents.size() + " events")));
    }

    // --- DTOs ---

    public record DlqListResponse(List<DlqEventDto> events, long total) {
        static DlqListResponse of(List<OutboxEvent> events, long total) {
            return new DlqListResponse(events.stream().map(DlqEventDto::from).toList(), total);
        }
    }

    public record DlqEventDto(
        Long id,
        String aggregateType,
        Long aggregateId,
        String eventType,
        int attemptCount,
        String lastError,
        OffsetDateTime createdAt,
        OffsetDateTime processedAt
    ) {
        /** Maximum characters of lastError surfaced in the API response. */
        private static final int MAX_ERROR_LEN = 200;

        static DlqEventDto from(OutboxEvent e) {
            return new DlqEventDto(
                e.getId(),
                e.getAggregateType(),
                e.getAggregateId(),
                e.getEventType(),
                e.getAttemptCount(),
                truncateError(e.getLastError()),
                e.getCreatedAt(),
                e.getProcessedAt()
            );
        }

        /**
         * Truncates raw exception text to avoid dumping multi-line stack traces into API responses.
         * Strips embedded newlines so the JSON field remains a single readable line.
         */
        private static String truncateError(String error) {
            if (error == null) {
                return null;
            }
            // Collapse newlines/carriage-returns into a space before truncating.
            String flat = error.replace('\n', ' ').replace('\r', ' ');
            return flat.length() > MAX_ERROR_LEN ? flat.substring(0, MAX_ERROR_LEN) : flat;
        }
    }

    public record RequeueResponse(int requeued, String message) {}
}
