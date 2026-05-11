package com.olive.commerce.admin;

import com.olive.commerce.common.api.ApiResponse;
import com.olive.commerce.search.OutboxEvent;
import com.olive.commerce.search.ProductIndexEnqueuer;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 검색 인덱스 관리자 API (OLV-100).
 *
 * <p>경로: {@code /api/admin/search}
 * <p>권한: {@code PRODUCT_ADMIN}
 *
 * <p>직접 OpenSearch에 동기 인덱스하지 않고 outbox에 행을 추가한다 — 일반
 * 변경 경로와 동일한 파이프라인을 사용해 장애 처리/관측을 통일.
 */
@RestController
@RequestMapping("/api/admin/search")
public class SearchAdminController {

    private final ProductIndexEnqueuer indexEnqueuer;

    public SearchAdminController(ProductIndexEnqueuer indexEnqueuer) {
        this.indexEnqueuer = indexEnqueuer;
    }

    /**
     * 단일 상품 재인덱스 요청. outbox에 PRODUCT_INDEX_SYNC 1건 enqueue 후 202.
     * 실제 인덱싱은 1초 이내 워커가 처리.
     */
    @PostMapping("/reindex/{productId}")
    @PreAuthorize("hasRole('PRODUCT_ADMIN')")
    @Transactional
    public ResponseEntity<ApiResponse<Map<String, Object>>> reindex(@PathVariable Long productId) {
        OutboxEvent event = indexEnqueuer.enqueueProductIndexSync(productId);
        Map<String, Object> body = Map.of(
            "productId", productId,
            "outboxEventId", event.getId(),
            "status", event.getStatus().name()
        );
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.success(body));
    }
}
