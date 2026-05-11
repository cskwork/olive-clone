package com.olive.commerce.cart;

import com.olive.commerce.common.api.ApiResponse;
import com.olive.commerce.common.security.AuthenticatedUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Cart API Controller (PRD §8.2).
 *
 * <p>회원 장바구니 + 익명 장바구니 API.
 * 세션 ID 기반 익명 카트는 X-Session-ID 헤더 또는 쿠키에서 추출.
 */
@RestController
@RequestMapping("/api/cart")
public class CartController {

    private final CartService cartService;

    public CartController(CartService cartService) {
        this.cartService = cartService;
    }

    // ========================================================================
    // 회원 장바구니 API
    // ========================================================================

    /**
     * POST /api/cart/items — 회원 장바구니에 아이템 추가.
     */
    @PostMapping("/items")
    public ResponseEntity<ApiResponse<CartDtos.AddItemResponse>> addMemberItem(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody CartDtos.AddItemRequest request) {
        CartDtos.AddItemResponse response = cartService.addMemberItem(principal.memberId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(response));
    }

    /**
     * GET /api/cart — 회원 장바구니 조회.
     */
    @GetMapping
    public ApiResponse<CartDtos.CartResponse> getMemberCart(
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return ApiResponse.success(cartService.getMemberCart(principal.memberId()));
    }

    /**
     * PATCH /api/cart/items/{cartItemId} — 회원 장바구니 아이템 수량 수정.
     */
    @PatchMapping("/items/{cartItemId}")
    public ApiResponse<Void> updateMemberItemQuantity(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable Long cartItemId,
            @Valid @RequestBody CartDtos.UpdateQuantityRequest request) {
        cartService.updateMemberItemQuantity(principal.memberId(), cartItemId, request);
        return ApiResponse.success(null);
    }

    /**
     * DELETE /api/cart/items/{cartItemId} — 회원 장바구니 아이템 삭제.
     */
    @DeleteMapping("/items/{cartItemId}")
    public ApiResponse<Void> removeMemberItem(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable Long cartItemId) {
        cartService.removeMemberItem(principal.memberId(), cartItemId);
        return ApiResponse.success(null);
    }

    // ========================================================================
    // 익명 장바구니 API (세션 ID 기반)
    // ========================================================================

    /**
     * POST /api/cart/anonymous/items — 익명 장바구니에 아이템 추가.
     */
    @PostMapping("/anonymous/items")
    public ResponseEntity<ApiResponse<CartDtos.AddItemResponse>> addAnonymousItem(
            HttpServletRequest request,
            @Valid @RequestBody CartDtos.AddItemRequest req) {
        String sessionId = getSessionId(request);
        CartDtos.AddItemResponse response = cartService.addAnonymousItem(sessionId, req);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(response));
    }

    /**
     * GET /api/cart/anonymous — 익명 장바구니 조회.
     */
    @GetMapping("/anonymous")
    public ApiResponse<CartDtos.CartResponse> getAnonymousCart(HttpServletRequest request) {
        String sessionId = getSessionId(request);
        return ApiResponse.success(cartService.getAnonymousCart(sessionId));
    }

    /**
     * PATCH /api/cart/anonymous/items/{optionId} — 익명 장바구니 아이템 수량 수정.
     */
    @PatchMapping("/anonymous/items/{optionId}")
    public ApiResponse<Void> updateAnonymousItemQuantity(
            HttpServletRequest request,
            @PathVariable Long optionId,
            @Valid @RequestBody CartDtos.UpdateQuantityRequest req) {
        String sessionId = getSessionId(request);
        cartService.updateAnonymousItemQuantity(sessionId, optionId, req.quantity());
        return ApiResponse.success(null);
    }

    /**
     * DELETE /api/cart/anonymous/items/{optionId} — 익명 장바구니 아이템 삭제.
     */
    @DeleteMapping("/anonymous/items/{optionId}")
    public ApiResponse<Void> removeAnonymousItem(
            HttpServletRequest request,
            @PathVariable Long optionId) {
        String sessionId = getSessionId(request);
        cartService.removeAnonymousItem(sessionId, optionId);
        return ApiResponse.success(null);
    }

    // ========================================================================
    // 장바구니 병합 API
    // ========================================================================

    /**
     * POST /api/cart/merge — 익명 장바구니를 회원 장바구니로 병합.
     */
    @PostMapping("/merge")
    public ApiResponse<CartDtos.MergeResponse> mergeCart(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody CartDtos.MergeRequest request) {
        CartDtos.MergeResponse response = cartService.mergeCart(principal.memberId(), request.sessionId());
        return ApiResponse.success(response);
    }

    // ========================================================================
    // Helper Methods
    // ========================================================================

    /**
     * HTTP 요청에서 세션 ID 추출.
     *
     * <p>우선순위:
     * 1. X-Session-ID 헤더
     * 2. JSESSIONID 쿠키
     * 3. 생성된 새 세션 ID
     */
    private String getSessionId(HttpServletRequest request) {
        String sessionId = request.getHeader("X-Session-ID");
        if (sessionId != null && !sessionId.isBlank()) {
            return sessionId;
        }

        jakarta.servlet.http.Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (jakarta.servlet.http.Cookie cookie : cookies) {
                if ("JSESSIONID".equals(cookie.getName())) {
                    return cookie.getValue();
                }
            }
        }

        // 세션이 없으면 빈 문자열 반환 (호출 측에서 새 세션 ID 생성 필요)
        return "";
    }
}
