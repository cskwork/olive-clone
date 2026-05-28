package com.olive.commerce.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;

/**
 * 커머스 도메인 정책 설정.
 *
 * <p>application.yml에서 {@code olive.domain.*} 네임스페이스로 설정한다.
 * 미설정 시 아래 기본값을 그대로 사용하므로 기존 동작과 동일하다 (PRD §6.4, §8.3).
 */
@Component
@ConfigurationProperties("olive.domain")
public class DomainProperties {

    /**
     * 무료배송 기준 금액. 기본 30,000원 (PRD §8.3).
     * 주문 소계가 이 금액 미만이면 {@link #defaultShippingFee}를 부과한다.
     */
    private BigDecimal freeShippingThreshold = new BigDecimal("30000");

    /**
     * 기본 배송비. 기본 3,000원 (PRD §8.3).
     */
    private BigDecimal defaultShippingFee = new BigDecimal("3000");

    /**
     * 주문 생성 시 재고 예약 TTL. 기본 15분 (PRD §8.3).
     */
    private Duration reservationTtl = Duration.ofMinutes(15);

    /**
     * 익명 장바구니(Redis) TTL. 기본 30일 (PRD §6.4).
     */
    private Duration anonCartTtl = Duration.ofDays(30);

    public BigDecimal getFreeShippingThreshold() {
        return freeShippingThreshold;
    }

    public void setFreeShippingThreshold(BigDecimal freeShippingThreshold) {
        this.freeShippingThreshold = freeShippingThreshold;
    }

    public BigDecimal getDefaultShippingFee() {
        return defaultShippingFee;
    }

    public void setDefaultShippingFee(BigDecimal defaultShippingFee) {
        this.defaultShippingFee = defaultShippingFee;
    }

    public Duration getReservationTtl() {
        return reservationTtl;
    }

    public void setReservationTtl(Duration reservationTtl) {
        this.reservationTtl = reservationTtl;
    }

    public Duration getAnonCartTtl() {
        return anonCartTtl;
    }

    public void setAnonCartTtl(Duration anonCartTtl) {
        this.anonCartTtl = anonCartTtl;
    }
}
