package com.olive.commerce.cart;

/**
 * 익명 장바구니 아이템 (cart 도메인 내부용).
 */
record AnonymousCartItem(
    Long productOptionId,
    Integer quantity
) {}
