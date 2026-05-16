package com.olive.commerce.delivery;

import com.olive.commerce.common.persistence.PostgresIntegrationSupport;
import com.olive.commerce.common.security.AuthenticatedUser;
import com.olive.commerce.member.MemberRole;
import com.olive.commerce.order.Order;
import com.olive.commerce.order.OrderRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 배송 API 통합 테스트.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "aws.s3.region=us-east-1",
    "aws.s3.endpoint=http://localhost:4566",
    "olive.security.jwt.issuer=olive-commerce",
    "olive.security.jwt.access-ttl=PT30M",
    "olive.security.jwt.refresh-ttl=P14D",
    "olive.security.jwt.private-key-location=classpath:keys/app.key",
    "olive.security.jwt.public-key-location=classpath:keys/app.pub"
})
class DeliveryApiIT extends PostgresIntegrationSupport {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EntityManager em;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate txTemplate;

    @Autowired
    private DeliveryRepository deliveryRepository;

    @Autowired
    private DeliveryStatusHistoryRepository historyRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private DeliveryService deliveryService;

    private Long memberId;
    private Long addressId;
    private Long orderId;
    private String orderNo;

    @BeforeEach
    void setUp() {
        // txTemplate을 사용하여 명시적 트랜잭션 관리
        txTemplate.executeWithoutResult(tx -> {
            // 테스트 데이터 정리
            em.createNativeQuery("""
                TRUNCATE delivery_status_histories, deliveries,
                          order_status_histories, order_items, order_price_summaries,
                          orders, outbox_events, member_addresses, members, member_grades
                RESTART IDENTITY CASCADE
            """).executeUpdate();

            // member_grades seed data 재삽입
            em.createNativeQuery("""
                INSERT INTO member_grades (name, discount_rate, point_rate, benefit_description, sort_order)
                VALUES ('BASIC', 0.00, 1.00, '기본 등급', 1)
            """).executeUpdate();

            // 회원 생성
            memberId = ((Number) em.createNativeQuery("""
                    INSERT INTO members (email, password_hash, name, phone, status, grade_id)
                    VALUES (:email, :hash, :name, :phone, 'ACTIVE', 1)
                    RETURNING id
                    """)
                    .setParameter("email", "test@example.com")
                    .setParameter("hash", "$2a$12$test")
                    .setParameter("name", "테스터")
                    .setParameter("phone", "01012345678")
                    .getSingleResult()).longValue();

            // 배송지 생성
            addressId = ((Number) em.createNativeQuery("""
                    INSERT INTO member_addresses (member_id, recipient_name, phone, zipcode, address_main, address_detail, is_default)
                    VALUES (:memberId, :recipientName, :phone, :zipcode, :addressMain, :addressDetail, true)
                    RETURNING id
                    """)
                    .setParameter("memberId", memberId)
                    .setParameter("recipientName", "테스터")
                    .setParameter("phone", "01012345678")
                    .setParameter("zipcode", "12345")
                    .setParameter("addressMain", "서울시 강남구")
                    .setParameter("addressDetail", "101동 101호")
                    .getSingleResult()).longValue();

            // 주문 생성 - Order 엔티티 사용
            Order order = Order.create(memberId, addressId);
            order.setOrderNo("ORD-TEST-001");  // 테스트용 고유 orderNo
            order.setStatus("PAID");
            order.setPriceDetails(
                java.math.BigDecimal.valueOf(50000),  // totalProductAmount
                java.math.BigDecimal.ZERO,             // discountAmount
                java.math.BigDecimal.ZERO,             // pointUsedAmount
                java.math.BigDecimal.ZERO,             // deliveryFee
                java.math.BigDecimal.valueOf(50000)   // finalPaymentAmount
            );
            em.persist(order);
            em.flush();
            orderId = order.getId();
            orderNo = order.getOrderNo();  // 실제 저장된 orderNo 사용
        });
    }

    @AfterEach
    void tearDown() {
        // txTemplate을 사용하여 정리
        txTemplate.executeWithoutResult(tx -> {
            if (orderId != null) {
                em.createNativeQuery("""
                    DELETE FROM delivery_status_histories
                    WHERE delivery_id IN (SELECT id FROM deliveries WHERE order_id = :orderId)
                    """)
                    .setParameter("orderId", orderId)
                    .executeUpdate();
                em.createNativeQuery("DELETE FROM deliveries WHERE order_id = :orderId")
                    .setParameter("orderId", orderId)
                    .executeUpdate();
            }
        });
    }

    @Test
    @DisplayName("주문에 대한 배송 목록 조회")
    void getMyOrderDeliveries() throws Exception {
        // given - 배송 생성
        Delivery delivery = Delivery.create(orderId, addressId);
        delivery.setCarrierName("MOCK");
        delivery.assignInvoice("mock-invoice-123");
        deliveryRepository.save(delivery);
        Long deliveryId = delivery.getId();

        // 실제 DB에 저장된 orderNo를 사용
        Order savedOrder = orderRepository.findById(orderId).orElseThrow();
        String actualOrderNo = savedOrder.getOrderNo();
        System.out.println("DEBUG: Using actualOrderNo=" + actualOrderNo);

        // when & then
        mockMvc.perform(get("/api/me/orders/{orderNo}/deliveries", actualOrderNo)
                        .with(createTestAuthentication(memberId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.deliveries").isArray())
                .andExpect(jsonPath("$.data.deliveries", hasSize(1)))
                .andExpect(jsonPath("$.data.deliveries[0].id").value(deliveryId))
                .andExpect(jsonPath("$.data.deliveries[0].orderId").value(orderId))
                .andExpect(jsonPath("$.data.deliveries[0].invoiceNo").value("mock-invoice-123"))
                .andExpect(jsonPath("$.data.deliveries[0].status").value("INVOICE"));
    }

    @Test
    @DisplayName("배송 상세 조회 (상태 이력 포함)")
    void getMyDeliveryDetail() throws Exception {
        // given
        Delivery delivery = Delivery.create(orderId, addressId);
        delivery.setCarrierName("MOCK");
        delivery.assignInvoice("mock-invoice-123");
        deliveryRepository.save(delivery);

        // 초기 이력
        DeliveryStatusHistory initialHistory = DeliveryStatusHistory.initial(delivery);
        historyRepository.save(initialHistory);

        // 상태 변경 이력
        DeliveryStatusHistory transitionHistory = DeliveryStatusHistory.transition(
                delivery, "READY", "INVOICE", "운송장 발급"
        );
        historyRepository.save(transitionHistory);

        Long deliveryId = delivery.getId();

        // when & then
        mockMvc.perform(get("/api/me/orders/deliveries/{id}", deliveryId)
                        .with(createTestAuthentication(memberId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value(deliveryId))
                .andExpect(jsonPath("$.data.invoiceNo").value("mock-invoice-123"))
                .andExpect(jsonPath("$.data.statusHistories").isArray())
                .andExpect(jsonPath("$.data.statusHistories", hasSize(2)))
                .andExpect(jsonPath("$.data.statusHistories[0].toStatus").value("INVOICE"))
                .andExpect(jsonPath("$.data.statusHistories[0].reason").value("운송장 발급"))
                .andExpect(jsonPath("$.data.statusHistories[1].toStatus").value("READY"))
                .andExpect(jsonPath("$.data.statusHistories[1].reason").value("배송 생성"));
    }

    @Test
    @DisplayName("다른 회원의 배송 조회 실패")
    void getOtherMemberDelivery_Fail() throws Exception {
        // given
        Delivery delivery = Delivery.create(orderId, addressId);
        deliveryRepository.save(delivery);
        Long deliveryId = delivery.getId();

        // when & then - 다른 회원 ID로 조회 시도
        Long otherMemberId = memberId + 999;
        mockMvc.perform(get("/api/me/orders/deliveries/{id}", deliveryId)
                        .with(createTestAuthentication(otherMemberId)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("존재하지 않는 주문의 배송 조회 실패")
    void getNonExistentOrderDeliveries_Fail() throws Exception {
        // when & then
        mockMvc.perform(get("/api/me/orders/{orderNo}/deliveries", "NONEXISTENT")
                        .with(createTestAuthentication(memberId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("ORDER_NOT_FOUND"));
    }

    @Test
    @DisplayName("PaymentApprovedEvent 수신 시 배송 준비 상태로 Delivery 생성")
    void whenPaymentApproved_thenCreatesDeliveryWithReadyStatus() {
        // when
        Long deliveryId = deliveryService.prepareForOrder(orderId);

        // then
        List<Delivery> deliveries = deliveryRepository.findByOrderId(orderId);
        assertThat(deliveries).hasSize(1);

        Delivery delivery = deliveries.get(0);
        assertThat(delivery.getId()).isEqualTo(deliveryId);
        assertThat(delivery.getStatus()).isEqualTo(Delivery.DeliveryStatus.READY);
        assertThat(delivery.getOrderId()).isEqualTo(orderId);
        assertThat(delivery.getDeliveryAddressId()).isEqualTo(addressId);
    }

    /**
     * 테스트용 인증 객체 생성 헬퍼.
     */
    private RequestPostProcessor createTestAuthentication(Long testMemberId) {
        MemberRole role = MemberRole.USER;
        AuthenticatedUser principal = new AuthenticatedUser(testMemberId, role);

        // Mock JWT를 생성하여 principal의 credential으로 설정
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

        return authentication(authentication);
    }
}
