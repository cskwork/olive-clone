package com.olive.commerce.promotion;

import com.olive.commerce.common.api.ApiResponse;
import com.olive.commerce.common.api.PageMeta;
import com.olive.commerce.common.security.AuthenticatedUser;
import com.olive.commerce.promotion.PointDtos.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 포인트 컨트롤러.
 * <p>회원용 포인트 조회 API를 제공합니다.
 */
@RestController
@RequestMapping("/api/me/points")
public class PointController {

    private final PointService pointService;

    public PointController(PointService pointService) {
        this.pointService = pointService;
    }

    /**
     * 현재 포인트 잔액과 대기 중인 포인트 수를 조회합니다.
     *
     * @param memberId 인증된 회원 ID
     * @return 포인트 잔액 응답
     */
    @GetMapping
    public ApiResponse<BalanceResponse> getBalance(
            @AuthenticationPrincipal AuthenticatedUser principal
    ) {
        long memberId = principal.memberId();

        BigDecimal balance = pointService.spendableBalance(memberId, OffsetDateTime.now());
        List<PointHistory> pending = pointService.getPendingPoints(memberId, 30);

        BalanceResponse response = new BalanceResponse(balance, pending.size());
        return ApiResponse.success(response);
    }

    /**
     * 포인트 사용 내역을 페이징 조회합니다.
     *
     * @param principal 인증된 사용자
     * @param page      페이지 번호 (0-based)
     * @param size      페이지 크기
     * @return 포인트 내역 페이지
     */
    @GetMapping("/history")
    public ApiResponse<List<HistoryResponse>> getHistory(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        long memberId = principal.memberId();

        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        Page<PointHistory> result = pointService.getHistory(memberId, pageable);

        List<HistoryResponse> histories = result.getContent().stream()
                .map(HistoryResponse::from)
                .toList();

        PageMeta meta = new PageMeta(page, size, result.getTotalElements());
        return ApiResponse.success(histories, meta);
    }
}
