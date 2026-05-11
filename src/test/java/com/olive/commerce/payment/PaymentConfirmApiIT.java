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
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionTemplate;

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

        // 테스트 데이터 정리 - OrderCreationApiIT와 동일한 패턴 (EntityManager 사용)
        transactionTemplate.executeWithoutResult(tx -> {
            em.createNativeQuery("""
                TRUNCATE payment_transactions, payments,
                          outbox_events, order_status_histories,
                          orders, inventory_reservations, inventory_histories, inventories,
                          member_addresses, members, member_grades
                RESTART IDENTITY CASCADE
                """).executeUpdate();

            // member_grades seed data
            em.createNativeQuery("""
                    INSERT INTO member_grades (name, discount_rate, point_rate, benefit_description, sort_order)
                    VALUES
                        ('BRONZE', 0.00, 1.00, '기본 등급', 1),
                        ('SILVER', 2.00, 2.00, '실버 등급', 2),
                        ('GOLD', 5.00, 3.00, '골드 등급', 3)
                    ON CONFLICT (name) DO NOTHING
                    """).executeUpdate();

            // 기본 회원/배송지 데이터 생성
            long bronzeGradeId = ((Number) em.createNativeQuery(
                    "SELECT id FROM member_grades WHERE name = 'BRONZE'"
            ).getSingleResult()).longValue();

            defaultMemberId = ((Number) em.createNativeQuery("""
                    INSERT INTO members (email, password_hash, name, phone, status, grade_id)
                    VALUES (?, ?, ?, ?, 'ACTIVE', ?)
                    RETURNING id
                    """)
                    .setParameter(1, "payment-test@example.com")
                    .setParameter(2, "$2a$12$test")
                    .setParameter(3, "결제테스트")
                    .setParameter(4, "01012345678")
                    .setParameter(5, bronzeGradeId)
                    .getSingleResult()).longValue();

            defaultAddressId = ((Number) em.createNativeQuery("""
                    INSERT INTO member_addresses (member_id, recipient_name, phone, zipcode, address_main, address_detail, is_default)
                    VALUES (?, ?, ?, ?, ?, ?, true)
                    RETURNING id
                    """)
                    .setParameter(1, defaultMemberId)
                    .setParameter(2, "홍길동")
                    .setParameter(3, "01012345678")
                    .setParameter(4, "12345")
                    .setParameter(5, "서울시 강남구")
                    .setParameter(6, "101호")
                    .getSingleResult()).longValue();
        });
    }

    private Long defaultMemberId;
    private Long defaultAddressId;

    @AfterEach
    void tearDown() {
        transactionTemplate.executeWithoutResult(tx -> {
            jdbcTemplate.execute("""
                TRUNCATE payment_transactions, payments,
                          outbox_events, order_status_histories,
                          orders, inventory_reservations, inventory_histories, inventories,
                          member_addresses, members, member_grades
                RESTART IDENTITY CASCADE
                """);
        });
    }

    private TestData createTestData(String orderNo) {
        // 직접 transactionManager를 사용하여 명시적으로 커밋
        DefaultTransactionDefinition def = new DefaultTransactionDefinition();
        def.setName("create-test-data");
        def.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        TestData result;
        var status = transactionManager.getTransaction(def);
        try {
            Long orderId = jdbcTemplate.queryForObject("""
                    INSERT INTO orders (member_id, delivery_address_id, order_no, status,
                                       total_product_amount, discount_amount, point_used_amount, delivery_fee, final_payment_amount)
                    VALUES (?, ?, ?, 'PAYMENT_PENDING', 50000, 0, 0, 3000, 35000)
                    RETURNING id
                    """, Long.class, defaultMemberId, defaultAddressId, orderNo);

            UUID paymentUuid = UUID.randomUUID();
            Long paymentId = jdbcTemplate.queryForObject("""
                    INSERT INTO payments (order_id, method, status, requested_amount, idempotency_key)
                    VALUES (?, 'CARD', 'REQUESTED', 35000, ?)
                    RETURNING id
                    """, Long.class, orderId, paymentUuid);

            result = new TestData(defaultMemberId, defaultAddressId, orderId, paymentId);

            // 명시적으로 커밋
            transactionManager.commit(status);
        } catch (Exception e) {
            transactionManager.rollback(status);
            throw new RuntimeException("Failed to create test data", e);
        }

        // 커밋 후 데이터가 있는지 확인 (별도 트랜잭션)
        Long verifyId = jdbcTemplate.queryForObject(
                "SELECT id FROM orders WHERE order_no = ?", Long.class, orderNo);
        System.out.println("DEBUG: After commit, orderId = " + verifyId + ", expected = " + result.orderId());

        return result;
    }

    private record TestData(Long memberId, Long addressId, Long orderId, Long paymentId) {}

    @Test
    @DisplayName("Happy path: PAYMENT_PENDING → PAID, 모든 side effects 완료")
    void happyPath_paymentPending_to_paid() throws Exception {
        String orderNo = "ORD-HAPPY-" + UUID.randomUUID().toString().substring(0, 8);
        TestData data = createTestData(orderNo);

        // 디버깅: 데이터가 실제로 DB에 있는지 확인
        Long orderIdFromDb = jdbcTemplate.queryForObject(
                "SELECT id FROM orders WHERE order_no = ?", Long.class, orderNo);
        System.out.println("DEBUG: orderIdFromDb = " + orderIdFromDb + ", expected = " + data.orderId());

        UUID idempotencyKey = UUID.randomUUID();
        String paymentKey = "mock-payment-key-123";

        String requestBody = String.format("""
                {
                    "orderNo": "%s",
                    "paymentKey": "%s",
                    "amount": 35000
                }
                """, orderNo, paymentKey);

        MvcResult result = mockMvc.perform(post("/api/payments/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody)
                        .header("Idempotency-Key", idempotencyKey.toString())
                        .with(createTestAuthentication(data.memberId())))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);

        String response = result.getResponse().getContentAsString();
        Map<String, Object> responseMap = objectMapper.readValue(response, Map.class);
        Map<String, Object> responseData = (Map<String, Object>) responseMap.get("data");

        assertThat(responseData.get("orderNo")).isEqualTo(orderNo);
        assertThat(responseData.get("status")).isEqualTo("PAID");
        assertThat(responseData.get("paymentKey")).isEqualTo(paymentKey);

        String orderStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM orders WHERE order_no = ?", String.class, orderNo);
        assertThat(orderStatus).isEqualTo("PAID");

        List<Map<String, Object>> payments = jdbcTemplate.queryForList(
                "SELECT status, payment_key FROM payments WHERE order_id = ?", data.orderId());
        assertThat(payments).hasSize(1);
        assertThat(payments.get(0).get("status")).isEqualTo("APPROVED");
        assertThat(payments.get(0).get("payment_key")).isEqualTo(paymentKey);

        Long txCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payment_transactions WHERE payment_id = ? AND kind = 'APPROVE' AND idempotency_key = ?",
                Long.class, data.paymentId(), idempotencyKey);
        assertThat(txCount).isEqualTo(1L);

        Long outboxCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE event_type = 'PAYMENT_APPROVED' AND aggregate_id = ?",
                Long.class, data.paymentId());
        assertThat(outboxCount).isEqualTo(1L);
    }

    @Test
    @DisplayName("같은 Idempotency-Key로 재요청 시 PG를 호출하지 않고 캐시된 응답 반환")
    void replayWithSameIdempotencyKey_returnsCachedResponse() throws Exception {
        String orderNo = "ORD-IDEM-" + UUID.randomUUID().toString().substring(0, 8);
        TestData data = createTestData(orderNo);
        UUID idempotencyKey = UUID.randomUUID();
        String paymentKey = "mock-payment-key-456";

        String requestBody = String.format("""
                {
                    "orderNo": "%s",
                    "paymentKey": "%s",
                    "amount": 35000
                }
                """, orderNo, paymentKey);

        MvcResult result1 = mockMvc.perform(post("/api/payments/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody)
                        .header("Idempotency-Key", idempotencyKey.toString())
                        .with(createTestAuthentication(data.memberId())))
                .andReturn();

        assertThat(result1.getResponse().getStatus()).isEqualTo(200);

        MvcResult result2 = mockMvc.perform(post("/api/payments/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody)
                        .header("Idempotency-Key", idempotencyKey.toString())
                        .with(createTestAuthentication(data.memberId())))
                .andReturn();

        assertThat(result2.getResponse().getStatus()).isEqualTo(200);

        Long count = jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM payment_transactions
            WHERE payment_id = ? AND kind = 'APPROVE'
        """, Long.class, data.paymentId());
        assertThat(count).isEqualTo(1L);
    }

    @Test
    @DisplayName("이미 PAID 상태인 주문에 confirm 요청 시 200 + 원래 paymentKey 반환")
    void alreadyPaidOrder_returnsCachedResponse() throws Exception {
        String orderNo = "ORD202605100001";
        TestData data = createTestData(orderNo);
        UUID idempotencyKey1 = UUID.randomUUID();
        String paymentKey = "pg-key-789";

        String requestBody = String.format("""
                {
                    "orderNo": "%s",
                    "paymentKey": "%s",
                    "amount": 35000
                }
                """, orderNo, paymentKey);

        MvcResult result1 = mockMvc.perform(post("/api/payments/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody)
                        .header("Idempotency-Key", idempotencyKey1.toString())
                        .with(createTestAuthentication(data.memberId())))
                .andReturn();

        assertThat(result1.getResponse().getStatus()).isEqualTo(200);

        String requestBody2 = String.format("""
                {
                    "orderNo": "%s",
                    "paymentKey": "different-key",
                    "amount": 35000
                }
                """, orderNo);

        MvcResult result2 = mockMvc.perform(post("/api/payments/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody2)
                        .with(createTestAuthentication(data.memberId())))
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
        String orderNo = "ORD202605100001";
        TestData data = createTestData(orderNo);
        UUID idempotencyKey = UUID.randomUUID();

        String requestBody = String.format("""
                {
                    "orderNo": "%s",
                    "paymentKey": "pg-key-mismatch",
                    "amount": 99999
                }
                """, orderNo);

        MvcResult result = mockMvc.perform(post("/api/payments/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody)
                        .header("Idempotency-Key", idempotencyKey.toString())
                        .with(createTestAuthentication(data.memberId())))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(422);

        Map<String, Object> response = objectMapper.readValue(
            result.getResponse().getContentAsString(), Map.class);
        Map<String, Object> error = (Map<String, Object>) response.get("error");

        assertThat(error.get("code")).isEqualTo("PAYMENT_AMOUNT_MISMATCH");

        String orderStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM orders WHERE id = ?", String.class, data.orderId());
        assertThat(orderStatus).isEqualTo("PAYMENT_PENDING");

        String paymentStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM payments WHERE id = ?", String.class, data.paymentId());
        assertThat(paymentStatus).isEqualTo("REQUESTED");
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
