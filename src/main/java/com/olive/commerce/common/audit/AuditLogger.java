package com.olive.commerce.common.audit;

import java.util.Map;

public interface AuditLogger {

    /**
     * 단일 라인 JSON 감사 로그를 일자 롤링 파일에 기록한다.
     *
     * 카테고리(이벤트 명) 가이드: LOGIN_SUCCESS / LOGIN_FAILURE /
     * ADMIN_MUTATION / PAYMENT_APPROVED / PAYMENT_FAILED /
     * INVENTORY_RESERVED / INVENTORY_RELEASED / COUPON_USED 등
     * (PRD §16.2). 카테고리 enum은 사용처(도메인 티켓)에서 점진적으로 도입한다.
     */
    void log(String event, Map<String, Object> attributes);
}
