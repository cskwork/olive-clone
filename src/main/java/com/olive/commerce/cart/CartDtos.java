package com.olive.commerce.cart;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.List;

/**
 * Cart API DTOs (PRD §8.2).
 */
public class CartDtos {

    /**
     * 장바구니 아이템 추가 요청.
     */
    public record AddItemRequest(
        @JsonProperty("productOptionId")
        @NotNull(message = "productOptionId is required")
        Long productOptionId,

        @JsonProperty("quantity")
        @Min(value = 1, message = "quantity must be at least 1")
        Integer quantity
    ) {
        public AddItemRequest {
            if (quantity == null) {
                quantity = 1;
            }
        }
    }

    /**
     * 장바구니 아이템 수량 수정 요청.
     */
    public record UpdateQuantityRequest(
        @JsonProperty("quantity")
        @NotNull(message = "quantity is required")
        @Min(value = 1, message = "quantity must be at least 1")
        Integer quantity
    ) {}

    /**
     * 장바구니 아이템 추가 응답.
     */
    public record AddItemResponse(
        @JsonProperty("cartItemId")
        Long cartItemId,

        @JsonProperty("quantity")
        Integer quantity
    ) {}

    /**
     * 장바구니 아이템 조회 응답 (최신 가격/상태 포함).
     */
    public record CartItemResponse(
        @JsonProperty("cartItemId")
        Long cartItemId,

        @JsonProperty("productOptionId")
        Long productOptionId,

        @JsonProperty("optionName")
        String optionName,

        @JsonProperty("productName")
        String productName,

        @JsonProperty("salePrice")
        BigDecimal salePrice,

        @JsonProperty("onSale")
        boolean onSale,

        @JsonProperty("availableQuantity")
        Integer availableQuantity,

        @JsonProperty("quantity")
        Integer quantity,

        @JsonProperty("lineSubtotal")
        BigDecimal lineSubtotal,

        @JsonProperty("productStatus")
        String productStatus
    ) {}

    /**
     * 장바구니 조회 응답.
     */
    public record CartResponse(
        @JsonProperty("items")
        List<CartItemResponse> items,

        @JsonProperty("totalItemCount")
        Integer totalItemCount,

        @JsonProperty("totalAmount")
        BigDecimal totalAmount
    ) {}

    /**
     * 장바구니 병합 요청.
     */
    public record MergeRequest(
        @JsonProperty("sessionId")
        @NotNull(message = "sessionId is required")
        String sessionId
    ) {}

    /**
     * 장바구니 병합 응답.
     */
    public record MergeResponse(
        @JsonProperty("mergedItemCount")
        Integer mergedItemCount,

        @JsonProperty("totalItemCount")
        Integer totalItemCount
    ) {}

    /**
     * 재고 부족 응답 (409 Conflict).
     */
    public record InsufficientStockResponse(
        @JsonProperty("availableQuantity")
        Integer availableQuantity,

        @JsonProperty("requestedQuantity")
        Integer requestedQuantity
    ) {}
}
