package com.olive.commerce.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.olive.commerce.payment.PaymentTransaction.TransactionKind;
import com.olive.commerce.payment.client.dto.ConfirmResponse;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 결제 트랜잭션 기록 컴포넌트.
 *
 * <p>PaymentService에서 분리한 PG 응답 및 웹훅 이벤트를 PaymentTransaction으로
 * 직렬화하여 저장하는 로직. 트랜잭션 없음 — 호출자의 @Transactional 컨텍스트 내에서 실행된다.
 */
@Component
@RequiredArgsConstructor
public class PaymentTransactionRecorder {

    private static final Logger log = LoggerFactory.getLogger(PaymentTransactionRecorder.class);

    private final PaymentTransactionRepository transactionRepository;
    private final ObjectMapper objectMapper;

    /**
     * PG 확인 응답을 트랜잭션 레코드로 저장.
     *
     * @param payment        대상 Payment
     * @param kind           트랜잭션 종류 (APPROVE 등)
     * @param pgResponse     PG 응답 DTO
     * @param httpStatus     PG HTTP 응답 코드
     * @param idempotencyKey 멱등성 키 (선택)
     */
    public void record(Payment payment, TransactionKind kind,
                       ConfirmResponse pgResponse, int httpStatus,
                       UUID idempotencyKey) {
        try {
            ObjectNode response = objectMapper.createObjectNode();
            response.put("status", pgResponse.status());
            if (pgResponse.approvedAt() != null) {
                response.put("approvedAt", pgResponse.approvedAt().toString());
            }
            if (pgResponse.failedReason() != null) {
                response.put("failedReason", pgResponse.failedReason());
            }
            String responseJson = objectMapper.writeValueAsString(response);
            PaymentTransaction transaction = PaymentTransaction.clientRequest(
                    payment, kind, responseJson, httpStatus, idempotencyKey
            );
            transactionRepository.save(transaction);
        } catch (Exception e) {
            log.error("Failed to record transaction for payment {}: {}", payment.getId(), e.getMessage());
        }
    }

    /**
     * PG 웹훅 이벤트를 트랜잭션 레코드로 저장.
     *
     * @param payment   대상 Payment
     * @param request   웹훅 요청 DTO
     * @param signature 웹훅 서명
     */
    public void recordWebhook(Payment payment, PaymentDtos.WebhookRequest request,
                              String signature) {
        try {
            ObjectNode response = objectMapper.createObjectNode();
            response.put("paymentKey", request.paymentKey());
            response.put("status", request.status());
            if (request.approvedAt() != null) {
                response.put("approvedAt", request.approvedAt().toString());
            }
            if (request.approvedAmount() != null) {
                response.set("approvedAmount", objectMapper.valueToTree(request.approvedAmount()));
            }
            if (request.failedReason() != null) {
                response.put("failedReason", request.failedReason());
            }
            if (signature != null) {
                response.put("signature", signature);
            }
            String responseJson = objectMapper.writeValueAsString(response);
            PaymentTransaction transaction = PaymentTransaction.webhook(payment, responseJson, 200);
            transactionRepository.save(transaction);
        } catch (Exception e) {
            log.error("Failed to record webhook transaction for payment {}: {}",
                    payment.getId(), e.getMessage());
        }
    }
}
