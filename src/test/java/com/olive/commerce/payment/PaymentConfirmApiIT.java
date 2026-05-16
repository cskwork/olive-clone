package com.olive.commerce.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.olive.commerce.common.persistence.PostgresIntegrationSupport;
import com.olive.commerce.member.MemberRole;
import com.olive.commerce.common.security.AuthenticatedUser;
import com.olive.commerce.order.OrderRepository;
import com.olive.commerce.payment.client.MockPgClient;
import com.olive.commerce.search.OutboxEventRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.TransactionDefinition;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * OLV-072 Payment Confirm API 통합 테스트.
 * <p>
 * 각 테스트는 독립적으로 데이터를 생성하고 정리합니다.
 */
@SpringBootTest
@AutoConfigureMockMvc
// 트랜잭션 미사용: 각 데이터 생성에서 명시적으로 커밋
class PaymentConfirmApiIT extends PostgresIntegrationSupport {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PaymentController paymentController;  // 디버깅: 컨트롤러 로드 확인

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MockPgClient mockPgClient;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManager em;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void setUp() {
        mockPgClient.setBehaviour("approve");

        // 테스트 데이터 정리 및 생성 (TransactionTemplate으로 커밋)
        transactionTemplate.executeWithoutResult(status -> {
            // 테이블 정리
            jdbcTemplate.execute("""
                TRUNCATE payment_transactions, payments,
                          outbox_events, order_status_histories,
                          orders, inventory_reservations, inventory_histories, inventories,
                          member_addresses, members
                RESTART IDENTITY CASCADE
                """);

            // member_grades seed data
            jdbcTemplate.update("""
                INSERT INTO member_grades (name, discount_rate, point_rate, benefit_description, sort_order)
                VALUES
                    ('BRONZE', 0.00, 1.00, '기본 등급', 1),
                    ('SILVER', 2.00, 2.00, '실버 등급', 2),
                    ('GOLD', 5.00, 3.00, '골드 등급', 3)
                ON CONFLICT (name) DO NOTHING
                """);

            // 기본 회원/배송지 데이터 생성
            long bronzeGradeId = jdbcTemplate.queryForObject(
                "SELECT id FROM member_grades WHERE name = 'BRONZE'", Long.class);

            defaultMemberId = jdbcTemplate.queryForObject("""
                INSERT INTO members (email, password_hash, name, phone, status, grade_id)
                VALUES (?, ?, ?, ?, 'ACTIVE', ?)
                RETURNING id
                """, Long.class, "payment-test@example.com", "$2a$12$test", "결제테스트", "01012345678", bronzeGradeId);

            defaultAddressId = jdbcTemplate.queryForObject("""
                INSERT INTO member_addresses (member_id, recipient_name, phone, zipcode, address_main, address_detail, is_default)
                VALUES (?, ?, ?, ?, ?, ?, true)
                RETURNING id
                """, Long.class, defaultMemberId, "홍길동", "01012345678", "12345", "서울시 강남구", "101호");

            // 테스트용 주문 생성
            testOrderNo = "ORD202605100001";
            testOrderId = jdbcTemplate.queryForObject("""
                INSERT INTO orders (member_id, delivery_address_id, order_no, status,
                                   total_product_amount, discount_amount, point_used_amount, delivery_fee, final_payment_amount)
                VALUES (?, ?, ?, 'PAYMENT_PENDING', 50000, 0, 0, 3000, 35000)
                RETURNING id
                """, Long.class, defaultMemberId, defaultAddressId, testOrderNo);
            testOrderNo = jdbcTemplate.queryForObject(
                "SELECT order_no FROM orders WHERE id = ?", String.class, testOrderId);

            // 테스트용 결제 생성
            UUID paymentUuid = UUID.randomUUID();
            testPaymentId = jdbcTemplate.queryForObject("""
                INSERT INTO payments (order_id, method, status, requested_amount, idempotency_key)
                VALUES (?, 'CARD', 'REQUESTED', 35000, ?)
                RETURNING id
                """, Long.class, testOrderId, paymentUuid);
        });
    }

    private Long defaultMemberId;
    private Long defaultAddressId;
    private Long testOrderId;
    private Long testPaymentId;
    private String testOrderNo;

    @AfterEach
    void tearDown() {
        transactionTemplate.executeWithoutResult(status -> {
            jdbcTemplate.execute("""
                TRUNCATE payment_transactions, payments,
                          outbox_events, order_status_histories,
                          orders, inventory_reservations, inventory_histories, inventories,
                          member_addresses, members
                RESTART IDENTITY CASCADE
                """);
        });
    }

    @Test
    @DisplayName("Happy path: PAYMENT_PENDING → PAID, 모든 side effects 완료")
    void happyPath_paymentPending_to_paid() throws Exception {
        UUID idempotencyKey = UUID.randomUUID();
        String paymentKey = "mock-payment-key-123";

        String requestBody = String.format("""
                {
                    "orderNo": "%s",
                    "paymentKey": "%s",
                    "amount": 35000
                }
                """, testOrderNo, paymentKey);

        MvcResult result = mockMvc.perform(post("/api/payments/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody)
                        .header("Idempotency-Key", idempotencyKey.toString())
                        .with(createTestAuthentication(defaultMemberId)))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);

        String response = result.getResponse().getContentAsString();
        Map<String, Object> responseMap = objectMapper.readValue(response, Map.class);
        Map<String, Object> responseData = (Map<String, Object>) responseMap.get("data");

        assertThat(responseData.get("orderNo")).isEqualTo(testOrderNo);
        assertThat(responseData.get("status")).isEqualTo("PAID");
        assertThat(responseData.get("paymentKey")).isEqualTo(paymentKey);

        String orderStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM orders WHERE order_no = ?", String.class, testOrderNo);
        assertThat(orderStatus).isEqualTo("PAID");

        List<Map<String, Object>> payments = jdbcTemplate.queryForList(
                "SELECT status, payment_key FROM payments WHERE order_id = ?", testOrderId);
        assertThat(payments).hasSize(1);
        assertThat(payments.get(0).get("status")).isEqualTo("APPROVED");
        assertThat(payments.get(0).get("payment_key")).isEqualTo(paymentKey);

        Long txCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payment_transactions WHERE payment_id = ? AND kind = 'APPROVE' AND idempotency_key = ?",
                Long.class, testPaymentId, idempotencyKey);
        assertThat(txCount).isEqualTo(1L);

        Long outboxCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE event_type = 'PAYMENT_APPROVED' AND aggregate_id = ?",
                Long.class, testPaymentId);
        assertThat(outboxCount).isEqualTo(1L);
    }

    @Test
    @DisplayName("같은 Idempotency-Key로 재요청 시 PG를 호출하지 않고 캐시된 응답 반환")
    void replayWithSameIdempotencyKey_returnsCachedResponse() throws Exception {
        UUID idempotencyKey = UUID.randomUUID();
        String paymentKey = "mock-payment-key-456";

        String requestBody = String.format("""
                {
                    "orderNo": "%s",
                    "paymentKey": "%s",
                    "amount": 35000
                }
                """, testOrderNo, paymentKey);

        MvcResult result1 = mockMvc.perform(post("/api/payments/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody)
                        .header("Idempotency-Key", idempotencyKey.toString())
                        .with(createTestAuthentication(defaultMemberId)))
                .andReturn();

        assertThat(result1.getResponse().getStatus()).isEqualTo(200);

        MvcResult result2 = mockMvc.perform(post("/api/payments/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody)
                        .header("Idempotency-Key", idempotencyKey.toString())
                        .with(createTestAuthentication(defaultMemberId)))
                .andReturn();

        assertThat(result2.getResponse().getStatus()).isEqualTo(200);

        Long count = jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM payment_transactions
            WHERE payment_id = ? AND kind = 'APPROVE'
        """, Long.class, testPaymentId);
        assertThat(count).isEqualTo(1L);
    }

    @Test
    @DisplayName("이미 PAID 상태인 주문에 confirm 요청 시 200 + 원래 paymentKey 반환")
    void alreadyPaidOrder_returnsCachedResponse() throws Exception {
        UUID idempotencyKey1 = UUID.randomUUID();
        String paymentKey = "pg-key-789";

        String requestBody = String.format("""
                {
                    "orderNo": "%s",
                    "paymentKey": "%s",
                    "amount": 35000
                }
                """, testOrderNo, paymentKey);

        MvcResult result1 = mockMvc.perform(post("/api/payments/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody)
                        .header("Idempotency-Key", idempotencyKey1.toString())
                        .with(createTestAuthentication(defaultMemberId)))
                .andReturn();

        assertThat(result1.getResponse().getStatus()).isEqualTo(200);

        String requestBody2 = String.format("""
                {
                    "orderNo": "%s",
                    "paymentKey": "different-key",
                    "amount": 35000
                }
                """, testOrderNo);

        MvcResult result2 = mockMvc.perform(post("/api/payments/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody2)
                        .with(createTestAuthentication(defaultMemberId)))
                .andReturn();

        assertThat(result2.getResponse().getStatus()).isEqualTo(200);

        Map<String, Object> response = objectMapper.readValue(
            result2.getResponse().getContentAsString(), Map.class);
        Map<String, Object> responseData = (Map<String, Object>) response.get("data");

        assertThat(responseData.get("status")).isEqualTo("PAID");
        assertThat(responseData.get("paymentKey")).isEqualTo(paymentKey);
    }

    @Test
    @DisplayName("결제 금액 불일치 시 422 반환, 상태 변화 없음")
    void amountMismatch_returns422_noStateChange() throws Exception {
        UUID idempotencyKey = UUID.randomUUID();

        String requestBody = String.format("""
                {
                    "orderNo": "%s",
                    "paymentKey": "pg-key-mismatch",
                    "amount": 99999
                }
                """, testOrderNo);

        MvcResult result = mockMvc.perform(post("/api/payments/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody)
                        .header("Idempotency-Key", idempotencyKey.toString())
                        .with(createTestAuthentication(defaultMemberId)))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(422);

        Map<String, Object> response = objectMapper.readValue(
            result.getResponse().getContentAsString(), Map.class);
        Map<String, Object> error = (Map<String, Object>) response.get("error");

        assertThat(error.get("code")).isEqualTo("PAYMENT_AMOUNT_MISMATCH");

        String orderStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM orders WHERE id = ?", String.class, testOrderId);
        assertThat(orderStatus).isEqualTo("PAYMENT_PENDING");

        String paymentStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM payments WHERE id = ?", String.class, testPaymentId);
        assertThat(paymentStatus).isEqualTo("REQUESTED");
    }

    @Test
    @DisplayName("PG 웹훅은 JWT 없이도 유효한 raw body 서명으로 처리된다")
    void webhook_withValidRawBodySignature_withoutJwt_returns200() throws Exception {
        String paymentKey = "mock-webhook-valid-key";
        transactionTemplate.executeWithoutResult(status -> {
            jdbcTemplate.update("""
                UPDATE orders SET status = 'PAID' WHERE id = ?
                """, testOrderId);
            jdbcTemplate.update("""
                UPDATE payments
                SET status = 'APPROVED', payment_key = ?
                WHERE id = ?
                """, paymentKey, testPaymentId);
        });

        String requestBody = """
            {"paymentKey":"%s","status":"APPROVED","approvedAt":"2026-05-12T12:00:00","approvedAmount":35000}
            """.formatted(paymentKey).trim();
        String signature = mockPgClient.signWebhook(requestBody);

        MvcResult result = mockMvc.perform(post("/api/payments/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Webhook-Signature", signature)
                        .content(requestBody))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("PG 웹훅 서명이 잘못되면 401을 반환한다")
    void webhook_withInvalidSignature_returns401() throws Exception {
        String paymentKey = "mock-webhook-invalid-key";
        transactionTemplate.executeWithoutResult(status -> jdbcTemplate.update("""
            UPDATE payments SET payment_key = ? WHERE id = ?
            """, paymentKey, testPaymentId));

        String requestBody = """
            {"paymentKey":"%s","status":"APPROVED","approvedAt":"2026-05-12T12:00:00","approvedAmount":35000}
            """.formatted(paymentKey).trim();

        MvcResult result = mockMvc.perform(post("/api/payments/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Webhook-Signature", "invalid-signature")
                        .content(requestBody))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(401);
    }

    private RequestPostProcessor createTestAuthentication(Long testMemberId) {
        MemberRole role = MemberRole.USER;
        AuthenticatedUser principal = new AuthenticatedUser(testMemberId, role);

        Jwt mockJwt = Jwt.withTokenValue("mock-token")
                .header("alg", "none")
                .claim(JwtClaimNames.SUB, testMemberId.toString())
                .build();

        org.springframework.security.core.Authentication authentication =
            new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                principal,
                mockJwt,
                List.of(new SimpleGrantedAuthority("ROLE_USER"))
            );

        return SecurityMockMvcRequestPostProcessors.authentication(authentication);
    }
}
