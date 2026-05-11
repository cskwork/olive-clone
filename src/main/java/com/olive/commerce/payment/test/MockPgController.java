package com.olive.commerce.payment.test;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * QA용 Mock PG 웹훅 시뮬레이터.
 * PG가 우리의 웹훅을 호출하는 상황을 시뮬레이션한다.
 *
 * <p>실제 POST /api/payments/webhook은 OLV-073에서 구현된다.
 * 이 컨트롤러는 테스트에서 PG 웹훅을 트리거하는 용도로 사용된다.
 *
 * <p>profile이 local이고 olive.pg.provider=mock일 때만 활성화된다.
 */
@RestController
@RequestMapping("/api/_test/pg")
@ConditionalOnProperty(name = "olive.pg.provider", havingValue = "mock")
public class MockPgController {

    /**
     * PG 웹훅 시뮬레이션 엔드포인트.
     * QA 테스트에서 PG 웹훅을 트리거하기 위해 호출한다.
     *
     * @param body 웹훅 본문 (paymentKey, status)
     * @return 200 OK
     */
    @PostMapping("/webhook")
    public ResponseEntity<Map<String, String>> triggerWebhook(@RequestBody Map<String, Object> body) {
        // 실제 웹훅은 OLV-073에서 구현
        // 여기서는 시뮬레이션을 위한 엔드포인트만 제공
        String paymentKey = (String) body.get("paymentKey");
        String status = (String) body.get("status");

        return ResponseEntity.ok(Map.of(
            "message", "Webhook triggered",
            "paymentKey", paymentKey != null ? paymentKey : "N/A",
            "status", status != null ? status : "N/A"
        ));
    }

    /**
     * Mock PG의 동작 모드를 설정하는 엔드포인트.
     * HTTP 헤더 대신 이 엔드포인트를 사용할 수도 있다.
     *
     * @param body 동작 모드 (approve, fail, timeout)
     * @return 200 OK
     */
    @PostMapping("/behaviour")
    public ResponseEntity<Map<String, String>> setBehaviour(@RequestBody Map<String, String> body) {
        String behaviour = body.get("behaviour");
        // TODO: OLV-072에서 PaymentService와 연동 시 실제 MockPgClient에 설정 전달
        return ResponseEntity.ok(Map.of(
            "message", "Behaviour set to " + behaviour,
            "behaviour", behaviour != null ? behaviour : "default"
        ));
    }
}
