package com.olive.commerce.wishlist;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * 찜 목록 API DTOs (OLV-W01).
 */
public class WishlistDtos {

    /**
     * 찜 추가 요청.
     */
    public record AddRequest(
        @JsonProperty("productId")
        @NotNull(message = "productId is required")
        Long productId
    ) {}

    /**
     * 찜 목록 아이템 응답 (상품 요약 정보 포함).
     */
    public record WishlistItemResponse(
        @JsonProperty("wishlistItemId")
        Long wishlistItemId,

        @JsonProperty("productId")
        Long productId,

        @JsonProperty("productName")
        String productName,

        @JsonProperty("brandName")
        String brandName,

        @JsonProperty("salePrice")
        BigDecimal salePrice,

        @JsonProperty("originalPrice")
        BigDecimal originalPrice,

        @JsonProperty("thumbnailUrl")
        String thumbnailUrl
    ) {}
}
