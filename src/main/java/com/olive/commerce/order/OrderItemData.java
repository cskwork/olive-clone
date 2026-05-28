package com.olive.commerce.order;

import java.math.BigDecimal;

/**
 * 주문 상품 데이터 (주문 도메인 내부용).
 */
record OrderItemData(
        Long productId,
        Long productOptionId,
        int quantity,
        String productName,
        String optionName,
        BigDecimal unitPrice
) {}
