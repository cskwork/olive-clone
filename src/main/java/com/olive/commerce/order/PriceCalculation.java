package com.olive.commerce.order;

import java.math.BigDecimal;

/**
 * 주문 금액 계산 결과.
 */
record PriceCalculation(
        BigDecimal subtotal,
        BigDecimal couponDiscount,
        BigDecimal pointDiscount,
        BigDecimal shippingFee,
        BigDecimal grandTotal
) {}
