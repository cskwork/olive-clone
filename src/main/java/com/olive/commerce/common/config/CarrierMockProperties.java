package com.olive.commerce.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Mock 택배사 클라이언트 설정 (OLV-080).
 * <p>
 * application.yml에서 {@code carrier.mock.*} 네임스페이스로 설정한다.
 */
@Component
@ConfigurationProperties("carrier.mock")
public class CarrierMockProperties {

    /**
     * Mock 클라이언트 활성화 여부. 기본 {@code true}.
     */
    private boolean enabled = true;

    /**
     * 테스트용 즉시 상태 전이 모드. 기본 {@code false}.
     * true면 운송장 발급 후 즉시 배송중 상태로 전이합니다.
     */
    private boolean immediateMode = false;

    /**
     * 동작 모드.
     * <ul>
     *   <li>"success" (기본): 정상 동작</li>
     *   <li>"fail": 운송장 발급 실패</li>
     *   <li>"status-fail": 상태 조회 실패</li>
     * </ul>
     */
    private String behaviour = "success";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isImmediateMode() {
        return immediateMode;
    }

    public void setImmediateMode(boolean immediateMode) {
        this.immediateMode = immediateMode;
    }

    public String getBehaviour() {
        return behaviour;
    }

    public void setBehaviour(String behaviour) {
        this.behaviour = behaviour;
    }
}
