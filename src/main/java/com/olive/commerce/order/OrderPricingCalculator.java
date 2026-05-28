package com.olive.commerce.order;

import com.olive.commerce.common.config.DomainProperties;
import com.olive.commerce.promotion.CouponDtos.ValidatedCoupon;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * 주문 금액 계산기.
 *
 * <p>OrderService에서 분리한 순수 가격 계산 로직(소계, 쿠폰/포인트 차감, 배송비, 최종 금액).
 * DB/트랜잭션과 무관한 무상태 컴포넌트이므로 단위 테스트가 쉽다 (PRD §8.3).
 */
@Component
public class OrderPricingCalculator {

    private final DomainProperties domainProperties;

    public OrderPricingCalculator(DomainProperties domainProperties) {
        this.domainProperties = domainProperties;
    }

    /** 소계 = Σ (단가 × 수량). */
    public BigDecimal subtotal(List<OrderItemData> items) {
        return items.stream()
                .map(item -> item.unitPrice().multiply(BigDecimal.valueOf(item.quantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * 최종 금액 계산 (PRD §8.3 Step 6).
     *
     * <p>shipping_fee = 기본 배송비 if subtotal &lt; 무료배송 기준, else 0.
     * grand_total = max(0, subtotal - coupon - point + shipping). 모든 금액 scale 0, HALF_UP.
     */
    public PriceCalculation calculate(List<OrderItemData> items,
                                      ValidatedCoupon validatedCoupon,
                                      BigDecimal usePointAmount) {
        BigDecimal subtotal = subtotal(items);

        BigDecimal couponDiscount = validatedCoupon != null
                ? validatedCoupon.discountAmount()
                : BigDecimal.ZERO;
        BigDecimal pointDiscount = usePointAmount != null ? usePointAmount : BigDecimal.ZERO;

        BigDecimal shippingFee = subtotal.compareTo(domainProperties.getFreeShippingThreshold()) < 0
                ? domainProperties.getDefaultShippingFee()
                : BigDecimal.ZERO;

        BigDecimal grandTotal = subtotal
                .subtract(couponDiscount)
                .subtract(pointDiscount)
                .add(shippingFee)
                .max(BigDecimal.ZERO);

        int scale = 0;
        RoundingMode rounding = RoundingMode.HALF_UP;
        return new PriceCalculation(
                subtotal.setScale(scale, rounding),
                couponDiscount.setScale(scale, rounding),
                pointDiscount.setScale(scale, rounding),
                shippingFee.setScale(scale, rounding),
                grandTotal.setScale(scale, rounding));
    }
}
