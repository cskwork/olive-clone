package com.olive.commerce.wishlist;

import com.olive.commerce.common.api.ApiResponse;
import com.olive.commerce.common.api.PageMeta;
import com.olive.commerce.common.security.AuthenticatedUser;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 찜 목록 API Controller (OLV-W01).
 *
 * <p>회원 찜 목록 조회/추가/제거 API. /api/me/wishlist 하위에 매핑.
 */
@RestController
@RequestMapping("/api/me/wishlist")
public class WishlistController {

    private final WishlistService wishlistService;

    public WishlistController(WishlistService wishlistService) {
        this.wishlistService = wishlistService;
    }

    /**
     * GET /api/me/wishlist?page=&size= — 회원 찜 목록 조회 (paginated).
     */
    @GetMapping
    public ApiResponse<List<WishlistDtos.WishlistItemResponse>> list(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        Page<WishlistDtos.WishlistItemResponse> result = wishlistService.list(principal.memberId(), pageable);
        PageMeta meta = new PageMeta(result.getNumber(), result.getSize(), result.getTotalElements());
        return ApiResponse.success(result.getContent(), meta);
    }

    /**
     * POST /api/me/wishlist — 상품 찜 추가 (idempotent).
     */
    @PostMapping
    public ResponseEntity<ApiResponse<Void>> add(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody WishlistDtos.AddRequest request) {
        wishlistService.add(principal.memberId(), request.productId());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(null));
    }

    /**
     * DELETE /api/me/wishlist/{productId} — 상품 찜 제거.
     */
    @DeleteMapping("/{productId}")
    public ApiResponse<Void> remove(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable Long productId) {
        wishlistService.remove(principal.memberId(), productId);
        return ApiResponse.success(null);
    }
}
