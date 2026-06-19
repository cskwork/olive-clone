package com.olive.commerce.order;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.olive.commerce.common.api.ApiResponse;
import com.olive.commerce.common.persistence.PostgresIntegrationSupport;
import com.olive.commerce.common.security.AuthenticatedUser;
import com.olive.commerce.inventory.InventoryReservation;
import com.olive.commerce.inventory.InventoryReservationRepository;
import com.olive.commerce.member.MemberAddress;
import com.olive.commerce.member.MemberAddressRepository;
import com.olive.commerce.member.MemberRepository;
import com.olive.commerce.member.MemberRole;
import com.olive.commerce.promotion.MemberCoupon;
import com.olive.commerce.promotion.MemberCouponRepository;
import com.olive.commerce.promotion.PointHistory;
import com.olive.commerce.promotion.PointHistoryRepository;
import com.olive.commerce.search.OutboxEvent;
import com.olive.commerce.search.OutboxEventRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * OLV-062 주문 취소 통합 테스트.
 * <p>
 * 사용자 취소 및 관리자 강제 취소 흐름을 검증합니다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OrderCancelApiIT extends PostgresIntegrationSupport {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private EntityManager em;

    @Autowired
    private TransactionTemplate txTemplate;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private MemberAddressRepository memberAddressRepository;

    @Autowired
    private MemberCouponRepository memberCouponRepository;

    @Autowired
    private PointHistoryRepository pointHistoryRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderItemRepository orderItemRepository;

    @Autowired
    private OrderPriceSummaryRepository orderPriceSummaryRepository;

    @Autowired
    private OrderStatusHistoryRepository orderStatusHistoryRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private InventoryReservationRepository inventoryReservationRepository;

    @Autowired
    private OrderService orderService;

    @Autowired
    private com.olive.commerce.payment.PaymentRepository paymentRepository;

    private Long memberId;
    private Long addressId;
    private Long optionId;
    private Long couponId;

    @BeforeEach
    void setUp() {
        txTemplate.executeWithoutResult(tx -> {
            // 테스트 데이터 정리
            em.createNativeQuery("""
                TRUNCATE order_status_histories, order_items, order_price_summaries,
                          orders, outbox_events, inventory_reservations, inventory_histories,
                          member_coupons, point_histories, inventories,
                          brands, products, product_options, members, member_addresses,
                          coupons, member_grades, points, promotions, promotion_products,
                          product_category_mapping, categories
                RESTART IDENTITY CASCADE
                """).executeUpdate();

            // member_grades seed data 재삽입
            em.createNativeQuery("""
                    INSERT INTO member_grades (name, discount_rate, point_rate, benefit_description, sort_order)
                    VALUES
                        ('BRONZE', 0.00, 1.00, '기본 등급', 1),
                        ('SILVER', 2.00, 2.00, '실버 등급', 2),
                        ('GOLD', 5.00, 3.00, '골드 등급', 3)
                    """).executeUpdate();

            // 회원 등급 ID 조회 (BRONZE)
            long bronzeGradeId = ((Number) em.createNativeQuery(
                    "SELECT id FROM member_grades WHERE name = 'BRONZE'"
            ).getSingleResult()).longValue();

            // 회원 생성
            memberId = ((Number) em.createNativeQuery("""
                    INSERT INTO members (email, password_hash, name, phone, status, grade_id)
                    VALUES (:email, :hash, :name, :phone, 'ACTIVE', :gradeId)
                    RETURNING id
                    """)
                    .setParameter("email", "cancel-test@example.com")
                    .setParameter("hash", "$2a$12$test")
                    .setParameter("name", "취소테스트")
                    .setParameter("phone", "01099999999")
                    .setParameter("gradeId", bronzeGradeId)
                    .getSingleResult()).longValue();

            // 배송지 생성
            addressId = ((Number) em.createNativeQuery("""
                    INSERT INTO member_addresses (member_id, recipient_name, phone, zipcode, address_main, address_detail, is_default)
                    VALUES (:memberId, :recipientName, :phone, :zipcode, :addressMain, :addressDetail, true)
                    RETURNING id
                    """)
                    .setParameter("memberId", memberId)
                    .setParameter("recipientName", "홍길동")
                    .setParameter("phone", "01012345678")
                    .setParameter("zipcode", "12345")
                    .setParameter("addressMain", "서울시 강남구")
                    .setParameter("addressDetail", "테헤란로 123")
                    .getSingleResult()).longValue();

            // 브랜드 생성
            long brandId = ((Number) em.createNativeQuery("""
                    INSERT INTO brands (name, slug, logo_url, status)
                    VALUES ('OLIVE YOUNG', 'olive-young', 'https://s3.local/olive.png', 'ACTIVE')
                    RETURNING id
                    """).getSingleResult()).longValue();

            // 카테고리 생성
            long categoryId = ((Number) em.createNativeQuery("""
                    INSERT INTO categories (name, slug, parent_id, sort_order)
                    VALUES ('테스트 스킨케어', 'test-skin-care', NULL, 99)
                    RETURNING id
                    """).getSingleResult()).longValue();

            // 상품 생성
            long productId = ((Number) em.createNativeQuery("""
                    INSERT INTO products (brand_id, name, description, status, base_price, sale_price)
                    VALUES (:brandId, '테스트 상품', '설명', 'ON_SALE', 15000, 15000)
                    RETURNING id
                    """)
                    .setParameter("brandId", brandId)
                    .getSingleResult()).longValue();

            // 상품-카테고리 매핑
            em.createNativeQuery("""
                    INSERT INTO product_category_mapping (product_id, category_id)
                    VALUES (:productId, :categoryId)
                    """)
                    .setParameter("productId", productId)
                    .setParameter("categoryId", categoryId)
                    .executeUpdate();

            // 옵션 생성
            optionId = ((Number) em.createNativeQuery("""
                    INSERT INTO product_options (product_id, option_name, option_price, status)
                    VALUES (:productId, '기본 옵션', 15000, 'ON_SALE')
                    RETURNING id
                    """)
                    .setParameter("productId", productId)
                    .getSingleResult()).longValue();

            // 재고 생성
            em.createNativeQuery("""
                    INSERT INTO inventories (product_option_id, total_quantity, reserved_quantity)
                    VALUES (:optionId, 100, 0)
                    """)
                    .setParameter("optionId", optionId)
                    .executeUpdate();

            // 쿠폰 생성
            long now = System.currentTimeMillis() / 1000;
            couponId = ((Number) em.createNativeQuery("""
                    INSERT INTO coupons (name, discount_type, discount_value, min_order_amount, started_at, ended_at, status, max_issue_count, issued_count)
                    VALUES ('1000원 할인', 'FIXED_AMOUNT', 1000, 10000, to_timestamp(:now - 86400), to_timestamp(:now + 86400), 'ACTIVE', 1000, 1)
                    RETURNING id
                    """)
                    .setParameter("now", now)
                    .getSingleResult()).longValue();

            // 회원 쿠폰 생성
            em.createNativeQuery("""
                    INSERT INTO member_coupons (member_id, coupon_id, status, issued_at, expires_at)
                    VALUES (:memberId, :couponId, 'ISSUED', to_timestamp(:now - 3600), to_timestamp(:now + 86400))
                    """)
                    .setParameter("memberId", memberId)
                    .setParameter("couponId", couponId)
                    .setParameter("now", now)
                    .executeUpdate();

            // 포인트 적립
            em.createNativeQuery("""
                    INSERT INTO point_histories (member_id, change_type, amount, reason, available_at)
                    VALUES (:memberId, 'EARN', 10000, '초기 적립', to_timestamp(:now))
                    """)
                    .setParameter("memberId", memberId)
                    .setParameter("now", now)
                    .executeUpdate();
        });
    }

    /**
     * 테스트용 인증 생성 헬퍼.
     */
    private RequestPostProcessor testAuth(Long testMemberId, MemberRole role) {
        AuthenticatedUser principal = new AuthenticatedUser(testMemberId, role);

        Jwt mockJwt = Jwt.withTokenValue("mock-token")
                .header("alg", "none")
                .claim(JwtClaimNames.SUB, testMemberId.toString())
                .build();

        // role에 따라 올바른 권한 추가
        String authority = switch (role) {
            case ORDER_ADMIN -> "ROLE_ORDER_ADMIN";
            case SUPER_ADMIN -> "ROLE_SUPER_ADMIN";
            default -> "ROLE_USER";
        };

        org.springframework.security.core.Authentication auth =
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                        principal,
                        mockJwt,
                        java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority(authority))
                );

        return authentication(auth);
    }

    private RequestPostProcessor userAuth() {
        return testAuth(memberId, MemberRole.USER);
    }

    private RequestPostProcessor adminAuth() {
        return testAuth(999L, MemberRole.ORDER_ADMIN);
    }

    private RequestPostProcessor otherUserAuth() {
        return testAuth(999L, MemberRole.USER);
    }

    /**
     * Helper: PAYMENT_PENDING 상태의 주문 생성
     */
    private Long createPaymentPendingOrder() {
        return txTemplate.execute(tx -> {
            // 주문 생성
            String orderNo = orderService.createOrder(
                    memberId,
                    new OrderDtos.CreateOrderRequest(
                            java.util.List.of(new OrderDtos.CreateOrderRequest.OrderItemRequest(optionId, 1)),
                            null,
                            BigDecimal.ZERO,
                            addressId
                    ),
                    null
            ).orderNo();

            return orderRepository.findByOrderNo(orderNo).get().getId();
        });
    }

    /**
     * Helper: PAID 상태의 주문 생성
     */
    private Long createPaidOrder() {
        return txTemplate.execute(tx -> {
            // 주문 생성
            String orderNo = orderService.createOrder(
                    memberId,
                    new OrderDtos.CreateOrderRequest(
                            java.util.List.of(new OrderDtos.CreateOrderRequest.OrderItemRequest(optionId, 1)),
                            couponId,
                            new BigDecimal("1000"),
                            addressId
                    ),
                    null
            ).orderNo();

            // PAID 상태로 변경 + PG paymentKey 저장 (production processSuccessfulPayment과 동일)
            Order order = orderRepository.findByOrderNo(orderNo).get();
            order.toPaid();
            order.setPaymentKey(PAID_PAYMENT_KEY);
            orderRepository.save(order);

            approvePayment(order.getId(), PAID_PAYMENT_KEY);

            // 이력 기록
            OrderStatusHistory history = OrderStatusHistory.transition(
                    order,
                    "PAYMENT_PENDING",
                    "PAID",
                    OrderStatusHistory.ChangedByKind.SYSTEM,
                    null,
                    "결제 승인"
            );
            orderStatusHistoryRepository.save(history);

            return order.getId();
        });
    }

    private static final String PAID_PAYMENT_KEY = "mock-paid-key";

    /**
     * createOrder가 만든 REQUESTED payment 행을 APPROVED + paymentKey로 승인.
     * production의 processSuccessfulPayment가 하는 일을 테스트에서 재현한다.
     */
    private void approvePayment(Long orderId, String paymentKey) {
        em.createNativeQuery("""
                UPDATE payments
                SET status = 'APPROVED', payment_key = :paymentKey,
                    approved_amount = requested_amount
                WHERE order_id = :orderId
                """)
                .setParameter("paymentKey", paymentKey)
                .setParameter("orderId", orderId)
                .executeUpdate();
    }

    /**
     * Helper: PREPARING 상태의 주문 생성
     */
    private Long createPreparingOrder() {
        return txTemplate.execute(tx -> {
            // 주문 생성
            String orderNo = orderService.createOrder(
                    memberId,
                    new OrderDtos.CreateOrderRequest(
                            java.util.List.of(new OrderDtos.CreateOrderRequest.OrderItemRequest(optionId, 1)),
                            null,
                            BigDecimal.ZERO,
                            addressId
                    ),
                    null
            ).orderNo();

            // PAID → PREPARING 상태로 변경 + PG paymentKey 저장
            Order order = orderRepository.findByOrderNo(orderNo).get();
            order.toPaid();
            order.setPaymentKey(PAID_PAYMENT_KEY);
            orderRepository.save(order);

            approvePayment(order.getId(), PAID_PAYMENT_KEY);

            // 상태 전이: PAID
            orderStatusHistoryRepository.save(OrderStatusHistory.transition(
                    order, "PAYMENT_PENDING", "PAID",
                    OrderStatusHistory.ChangedByKind.SYSTEM, null, "결제 승인"
            ));

            // PREPARING 상태로 변경 (엔티티에 toPreparing 메서드가 없으므로 직접 변경)
            em.createNativeQuery("UPDATE orders SET status = 'PREPARING' WHERE id = :id")
                    .setParameter("id", order.getId())
                    .executeUpdate();

            // 상태 전이: PREPARING
            orderStatusHistoryRepository.save(OrderStatusHistory.transition(
                    order, "PAID", "PREPARING",
                    OrderStatusHistory.ChangedByKind.SYSTEM, null, "상품 준비중"
            ));

            return order.getId();
        });
    }

    /**
     * Helper: SHIPPING 상태의 주문 생성
     */
    private Long createShippingOrder() {
        return txTemplate.execute(tx -> {
            // 주문 생성
            String orderNo = orderService.createOrder(
                    memberId,
                    new OrderDtos.CreateOrderRequest(
                            java.util.List.of(new OrderDtos.CreateOrderRequest.OrderItemRequest(optionId, 1)),
                            null,
                            BigDecimal.ZERO,
                            addressId
                    ),
                    null
            ).orderNo();

            // SHIPPING 상태로 변경
            em.createNativeQuery("UPDATE orders SET status = 'SHIPPING' WHERE id = :id")
                    .setParameter("id", orderRepository.findByOrderNo(orderNo).get().getId())
                    .executeUpdate();

            return orderRepository.findByOrderNo(orderNo).get().getId();
        });
    }

    @Test
    void cancelOrder_fromPaymentPendingStatus_success() throws Exception {
        // Given: PAYMENT_PENDING 상태의 주문
        Long orderId = createPaymentPendingOrder();
        String orderNo = orderRepository.findById(orderId).get().getOrderNo();

        // When: 사용자가 주문 취소
        mockMvc.perform(post("/api/orders/{orderNo}/cancel", orderNo)
                        .with(userAuth())
                        .header("Idempotency-Key", "cancel-test-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("CANCELED"))
                .andExpect(jsonPath("$.data.orderNo").value(orderNo));

        // Then: 상태가 CANCELED로 변경
        Order order = orderRepository.findById(orderId).get();
        assertThat(order.getStatus()).isEqualTo(Order.OrderStatus.CANCELED);

        // Then: 감사 이력 기록
        var histories = orderStatusHistoryRepository.findByOrderIdOrderByCreatedAtDesc(orderId);
        assertThat(histories).anyMatch(h ->
                "CANCELED".equals(h.getToStatus()) &&
                h.getChangedByKind() == OrderStatusHistory.ChangedByKind.USER);

        // Then: 재고 예약 해제됨
        var reservations = inventoryReservationRepository.findByOrderId(orderId);
        assertThat(reservations).allMatch(r -> r.getStatus() == InventoryReservation.ReservationStatus.RELEASED);
    }

    @Test
    void cancelOrder_fromPaidStatus_success() throws Exception {
        // Given: PAID 상태의 주문 (쿠폰, 포인트 사용)
        Long orderId = createPaidOrder();
        String orderNo = orderRepository.findById(orderId).get().getOrderNo();

        // When: 사용자가 주문 취소
        mockMvc.perform(post("/api/orders/{orderNo}/cancel", orderNo)
                        .with(userAuth())
                        .header("Idempotency-Key", "cancel-test-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\": \"단순 변심\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("CANCELED"));

        // Then: 상태가 CANCELED로 변경
        Order order = orderRepository.findById(orderId).get();
        assertThat(order.getStatus()).isEqualTo(Order.OrderStatus.CANCELED);

        // Then: 쿠폰 복구됨
        MemberCoupon coupon = memberCouponRepository.findById(couponId).get();
        assertThat(coupon.getStatus()).isEqualTo(MemberCoupon.MemberCouponStatus.ISSUED);

        // Then: 포인트 복구됨 (CANCEL 내역)
        var pointHistories = pointHistoryRepository.findByOrderId(orderId);
        assertThat(pointHistories).anyMatch(h ->
                "CANCEL".equals(h.getChangeType().name()) &&
                h.getAmount().compareTo(BigDecimal.ZERO) > 0);
    }

    @Test
    void cancelOrder_fromPaidStatus_triggersPgCancelAndPaymentCanceled() throws Exception {
        // Given: PAID 상태 + 저장된 paymentKey를 가진 주문 (실제 청구된 결제)
        Long orderId = createPaidOrder();
        String orderNo = orderRepository.findById(orderId).get().getOrderNo();

        // 사전 조건: payment가 APPROVED이고 order에 paymentKey가 저장되어 있어야 함
        var payment = paymentRepository.findByOrderId(orderId).orElseThrow();
        assertThat(payment.getStatus()).isEqualTo(com.olive.commerce.payment.Payment.PaymentStatus.APPROVED);
        assertThat(orderRepository.findById(orderId).get().getPaymentKey()).isEqualTo("mock-paid-key");

        // When: 사용자가 주문 취소 → PG 취소 경로 트리거
        mockMvc.perform(post("/api/orders/{orderNo}/cancel", orderNo)
                        .with(userAuth())
                        .header("Idempotency-Key", "cancel-pg-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\": \"PG 취소 검증\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELED"));

        // Then: 주문 CANCELED
        assertThat(orderRepository.findById(orderId).get().getStatus())
                .isEqualTo(Order.OrderStatus.CANCELED);

        // Then: 저장된 paymentKey를 사용하여 결제가 PG에서 CANCELED 상태로 전이됨
        var canceledPayment = paymentRepository.findByOrderId(orderId).orElseThrow();
        assertThat(canceledPayment.getStatus())
                .isEqualTo(com.olive.commerce.payment.Payment.PaymentStatus.CANCELED);
        assertThat(canceledPayment.getPaymentKey()).isEqualTo("mock-paid-key");
    }

    @Test
    void cancelOrder_fromPreparingStatus_success() throws Exception {
        // Given: PREPARING 상태의 주문
        Long orderId = createPreparingOrder();
        String orderNo = orderRepository.findById(orderId).get().getOrderNo();

        // When: 사용자가 주문 취소
        mockMvc.perform(post("/api/orders/{orderNo}/cancel", orderNo)
                        .with(userAuth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        // Then: 상태가 CANCELED로 변경
        Order order = orderRepository.findById(orderId).get();
        assertThat(order.getStatus()).isEqualTo(Order.OrderStatus.CANCELED);
    }

    @Test
    void cancelOrder_fromShippingStatus_returns422() throws Exception {
        // Given: SHIPPING 상태의 주문
        Long orderId = createShippingOrder();
        String orderNo = orderRepository.findById(orderId).get().getOrderNo();

        // When & Then: 사용자가 취소 시도 → 422
        mockMvc.perform(post("/api/orders/{orderNo}/cancel", orderNo)
                        .with(userAuth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void cancelOrder_idempotent_returns200() throws Exception {
        // Given: PAID 상태의 주문
        Long orderId = createPaidOrder();
        String orderNo = orderRepository.findById(orderId).get().getOrderNo();

        // When: 첫 번째 취소
        mockMvc.perform(post("/api/orders/{orderNo}/cancel", orderNo)
                        .with(userAuth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());

        // When: 두 번째 취소 (멱등성)
        mockMvc.perform(post("/api/orders/{orderNo}/cancel", orderNo)
                        .with(userAuth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void cancelOrder_notOwned_returns403() throws Exception {
        // Given: 다른 회원의 주문
        Long orderId = createPaidOrder();
        String orderNo = orderRepository.findById(orderId).get().getOrderNo();

        // When & Then: 다른 회원이 취소 시도 → 403
        mockMvc.perform(post("/api/orders/{orderNo}/cancel", orderNo)
                        .with(otherUserAuth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminCancelOrder_fromShippingStatus_success() throws Exception {
        // Given: SHIPPING 상태의 주문
        Long orderId = createShippingOrder();

        // When: 관리자가 강제 취소
        mockMvc.perform(post("/api/admin/orders/{orderId}/cancel", orderId)
                        .with(adminAuth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\": \"고객 요청 (배송 지연)\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        // Then: 상태가 CANCELED로 변경
        Order order = orderRepository.findById(orderId).get();
        assertThat(order.getStatus()).isEqualTo(Order.OrderStatus.CANCELED);

        // Then: 감사 이력에 ADMIN 기록
        var histories = orderStatusHistoryRepository.findByOrderIdOrderByCreatedAtDesc(orderId);
        assertThat(histories).anyMatch(h ->
                "CANCELED".equals(h.getToStatus()) &&
                h.getChangedByKind() == OrderStatusHistory.ChangedByKind.ADMIN);
    }

    @Test
    void adminCancelOrder_missingReason_returns400() throws Exception {
        // Given: PAID 상태의 주문
        Long orderId = createPaidOrder();

        // When & Then: 사유 없이 관리자 취소 시도 → 400
        mockMvc.perform(post("/api/admin/orders/{orderId}/cancel", orderId)
                        .with(adminAuth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void adminCancelOrder_withoutOrderAdminRole_returns403() throws Exception {
        // Given: PAID 상태의 주문
        Long orderId = createPaidOrder();

        // When & Then: 일반 사용자가 관리자 엔드포인트 호출 → 403
        mockMvc.perform(post("/api/admin/orders/{orderId}/cancel", orderId)
                        .with(userAuth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\": \"테스트\"}"))
                .andExpect(status().isForbidden());
    }
}
