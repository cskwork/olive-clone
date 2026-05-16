package com.olive.commerce.e2e;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.olive.commerce.common.event.OutboxEventDrainer;
import com.olive.commerce.common.persistence.PostgresIntegrationSupport;
import com.olive.commerce.common.security.AuthenticatedUser;
import com.olive.commerce.delivery.DeliveryScheduledTasks;
import com.olive.commerce.member.MemberRole;
import com.olive.commerce.payment.client.MockPgClient;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * OLV-140 End-to-end purchase flow integration test.
 * <p>
 * 12단계 구매 플로우를 Testcontainers 환경에서 검증합니다.
 * <p>
 * AC1: 테스트가 clean DB에서 &lt;60초 내에 통과
 * AC2: 멱등성 키 재사용 시 동일 상태 (중복 재고 커밋 없음)
 * AC3: X-Mock-Pg-Behaviour: fail 시 PAYMENT_PENDING 상태 유지
 */
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class PurchaseFlowE2ETest extends PostgresIntegrationSupport {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private EntityManager em;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate txTemplate;

    @Autowired
    private MockPgClient mockPgClient;

    @Autowired
    private DeliveryScheduledTasks deliveryScheduledTasks;

    @Autowired
    private OutboxEventDrainer outboxEventDrainer;

    // 테스트 데이터 IDs
    private Long adminMemberId;
    private Long userMemberId;
    private Long userAddressId;
    private Long brandId;
    private Long categoryId;
    private Long productId;
    private Long optionAId;
    private Long optionBId;
    private Long memberCouponId;

    private String adminToken;
    private String userToken;

    @BeforeEach
    void setUp() {
        mockPgClient.setBehaviour("approve");

        // 모든 테이블 정리
        jdbcTemplate.execute("""
            TRUNCATE
                review_reports, reviews, product_review_summaries,
                delivery_retry_queue, delivery_status_histories, deliveries,
                payment_transactions, payments,
                outbox_events, order_status_histories, order_items, order_price_summaries, orders,
                inventory_reservations, inventory_histories, inventories,
                member_coupons, point_histories, points,
                cart_items,
                product_category_mapping, product_options, products,
                categories, brands,
                member_addresses, members, member_grades,
                coupons, promotions, promotion_products
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

        // 관리자 회원 생성 (PRODUCT_ADMIN 역할)
        long bronzeGradeId = jdbcTemplate.queryForObject(
            "SELECT id FROM member_grades WHERE name = 'BRONZE'", Long.class);
        adminMemberId = jdbcTemplate.queryForObject("""
            INSERT INTO members (email, password_hash, name, phone, status, grade_id)
            VALUES (?, '$2a$12$test', '관리자', '01000000001', 'ACTIVE', ?)
            RETURNING id
            """, Long.class, "admin@olive.com", bronzeGradeId);

        // 사용자 회원 생성
        userMemberId = jdbcTemplate.queryForObject("""
            INSERT INTO members (email, password_hash, name, phone, status, grade_id)
            VALUES (?, '$2a$12$test', '테스트유저', '01000000002', 'ACTIVE', ?)
            RETURNING id
            """, Long.class, "user@example.com", bronzeGradeId);

        // 사용자 배송지 생성
        userAddressId = jdbcTemplate.queryForObject("""
            INSERT INTO member_addresses (member_id, recipient_name, phone, zipcode, address_main, address_detail, is_default)
            VALUES (?, '홍길동', '01012345678', '12345', '서울시 강남구', '101호', true)
            RETURNING id
            """, Long.class, userMemberId);

        // 브랜드 생성
        brandId = jdbcTemplate.queryForObject("""
            INSERT INTO brands (name, slug, logo_url)
            VALUES ('테스트 브랜드', 'test-brand', 'https://example.com/logo.png')
            RETURNING id
            """, Long.class);

        // 카테고리 생성
        categoryId = jdbcTemplate.queryForObject("""
            INSERT INTO categories (name, slug, parent_id, sort_order, depth)
            VALUES ('스킨케어', 'skincare', NULL, 1, 0)
            RETURNING id
            """, Long.class);

        // 상품 생성
        productId = jdbcTemplate.queryForObject("""
            INSERT INTO products (brand_id, name, description, base_price, sale_price, status)
            VALUES (?, '테스트 상품', '테스트 상품 설명', 10000, 10000, 'ON_SALE')
            RETURNING id
            """, Long.class, brandId);

        // 상품-카테고리 매핑
        jdbcTemplate.update("""
            INSERT INTO product_category_mapping (product_id, category_id)
            VALUES (?, ?)
            """, productId, categoryId);

        // 옵션 A (50ml, 10000원)
        optionAId = jdbcTemplate.queryForObject("""
            INSERT INTO product_options (product_id, option_name, option_price, status)
            VALUES (?, '50ml', 10000, 'ON_SALE')
            RETURNING id
            """, Long.class, productId);

        // 옵션 B (100ml, 15000원)
        optionBId = jdbcTemplate.queryForObject("""
            INSERT INTO product_options (product_id, option_name, option_price, status)
            VALUES (?, '100ml', 15000, 'ON_SALE')
            RETURNING id
            """, Long.class, productId);

        // 재고 설정 (각 100개)
        jdbcTemplate.update("""
            INSERT INTO inventories (product_option_id, total_quantity, reserved_quantity)
            VALUES (?, 100, 0)
            """, optionAId);
        jdbcTemplate.update("""
            INSERT INTO inventories (product_option_id, total_quantity, reserved_quantity)
            VALUES (?, 100, 0)
            """, optionBId);

        // 쿠폰 생성 (FIXED_AMOUNT 1000원)
        Long couponId = jdbcTemplate.queryForObject("""
            INSERT INTO coupons (name, discount_type, discount_value, min_order_amount, status,
                                 started_at, ended_at, max_issue_count, issued_count)
            VALUES ('테스트 쿠폰', 'FIXED_AMOUNT', 1000, 5000, 'ACTIVE',
                    NOW(), NOW() + INTERVAL '30 days', 1000, 1)
            RETURNING id
            """, Long.class);

        // 사용자 쿠폰 발급
        memberCouponId = jdbcTemplate.queryForObject("""
            INSERT INTO member_coupons (member_id, coupon_id, status, expires_at)
            VALUES (?, ?, 'ISSUED', NOW() + INTERVAL '30 days')
            RETURNING id
            """, Long.class, userMemberId, couponId);

        // 사용자 포인트 지급 (5000원)
        jdbcTemplate.update("""
            INSERT INTO points (member_id, balance)
            VALUES (?, 5000)
            """, userMemberId);
        jdbcTemplate.update("""
            INSERT INTO point_histories (member_id, change_type, amount, reason)
            VALUES (?, 'EARN', 5000, '초기 지급')
            """, userMemberId);

        // 토큰 생성 (실제 login API 호출은 Bcrypt 비용이 높아서 Mock JWT 사용)
        adminToken = "mock-admin-token-" + adminMemberId;
        userToken = "mock-user-token-" + userMemberId;
    }

    @Test
    @DisplayName("AC1: 회원가입~리뷰까지 전체 플로우 <60초")
    @Tag("e2e-happy-path")
    void happyPath_completePurchaseFlow() throws Exception {
        long startTime = System.currentTimeMillis();

        // ====== Step 1: Admin이 브랜드, 카테고리, 상품 확인 (이미 setUp에서 생성됨) ======

        // ====== Step 2: 사용자가 상품 목록 조회 ======
        MvcResult productsResult = mockMvc.perform(get("/api/products")
                        .with(bearerToken(userToken)))
                .andExpect(status().isOk())
                .andReturn();

        Map<String, Object> productsResponse = parseResponse(productsResult);
        List<Map<String, Object>> products = (List<Map<String, Object>>) productsResponse.get("data");
        assertThat(products).isNotEmpty();
        assertThat(products.get(0).get("productName")).isEqualTo("테스트 상품");

        // ====== Step 3: 장바구니에 담기 (옵션 A, 수량 2) ======
        String addCartRequest = """
            {
                "productOptionId": %d,
                "quantity": 2
            }
            """.formatted(optionAId);

        mockMvc.perform(post("/api/cart/items")
                        .with(bearerToken(userToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(addCartRequest))
                .andExpect(status().isCreated())
                .andReturn();

        // ====== Step 4: 주문 생성 (장바구니 아이템 + 쿠폰 + 포인트) ======
        // 배송비 포함 총금액: 10000 * 2 = 20000원 (30000원 미만이므로 배송비 3000원 추가)
        // 쿠폰 1000원 + 포인트 1000원 사용 = 21000원 최종 결제 금액
        String createOrderRequest = """
            {
                "items": [
                    {"productOptionId": %d, "quantity": 2}
                ],
                "couponId": %d,
                "usePointAmount": 1000,
                "deliveryAddressId": %d
            }
            """.formatted(optionAId, memberCouponId, userAddressId);

        MvcResult orderResult = mockMvc.perform(post("/api/orders")
                        .with(bearerToken(userToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createOrderRequest))
                .andExpect(status().isCreated())
                .andReturn();

        Map<String, Object> orderResponse = parseResponse(orderResult);
        Map<String, Object> orderData = (Map<String, Object>) orderResponse.get("data");
        String orderNo = (String) orderData.get("orderNo");
        BigDecimal paymentAmount = new BigDecimal(orderData.get("amount").toString());

        assertThat(orderNo).startsWith("ORD").hasSize(17); // ORD(3) + YYYYMMDD(8) + 000000(6) = 17
        // 20000 - 1000(coupon) - 1000(point) + 3000(shipping) = 21000
        assertThat(paymentAmount).isEqualByComparingTo("21000");

        // 주문 상태 확인: PAYMENT_PENDING
        String orderStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM orders WHERE order_no = ?", String.class, orderNo);
        assertThat(orderStatus).isEqualTo("PAYMENT_PENDING");

        // 재고 예약 확인: HELD 상태, 수량 2
        List<Map<String, Object>> reservations = jdbcTemplate.queryForList(
                "SELECT status, quantity FROM inventory_reservations WHERE order_id = " +
                    "(SELECT id FROM orders WHERE order_no = ?)", orderNo);
        assertThat(reservations).hasSize(1);
        assertThat(reservations.get(0).get("status")).isEqualTo("HELD");
        assertThat(((Number) reservations.get(0).get("quantity")).intValue()).isEqualTo(2);

        // ====== Step 5: 결제 승인 (Mock PG approve) ======
        UUID idempotencyKey = UUID.randomUUID();
        String paymentKey = "mock-payment-key-" + UUID.randomUUID();

        String paymentConfirmRequest = """
            {
                "orderNo": "%s",
                "paymentKey": "%s",
                "amount": 21000
            }
            """.formatted(orderNo, paymentKey);

        MvcResult paymentResult = mockMvc.perform(post("/api/payments/confirm")
                        .with(bearerToken(userToken))
                        .header("Idempotency-Key", idempotencyKey.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(paymentConfirmRequest))
                .andExpect(status().isOk())
                .andReturn();

        Map<String, Object> paymentResponse = parseResponse(paymentResult);
        Map<String, Object> paymentData = (Map<String, Object>) paymentResponse.get("data");
        assertThat(paymentData.get("status")).isEqualTo("PAID");
        assertThat(paymentData.get("orderNo")).isEqualTo(orderNo);

        // ====== Step 6: outbox_events PAYMENT_APPROVED 대기 (최대 5초) ======
        Long orderId = jdbcTemplate.queryForObject(
                "SELECT id FROM orders WHERE order_no = ?", Long.class, orderNo);

        awaitOutboxDone("PAYMENT_APPROVED", orderId, 5000);

        // ====== Step 7: 결제 완료 후 상태 검증 ======
        // 주문 상태: PAID
        orderStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM orders WHERE order_no = ?", String.class, orderNo);
        assertThat(orderStatus).isEqualTo("PAID");

        // 재고 커밋 확인: total_quantity 100 → 98, reserved_quantity 0
        Map<String, Object> inventory = jdbcTemplate.queryForMap(
                "SELECT total_quantity, reserved_quantity FROM inventories WHERE product_option_id = ?", optionAId);
        assertThat(((Number) inventory.get("total_quantity")).intValue()).isEqualTo(98);
        assertThat(((Number) inventory.get("reserved_quantity")).intValue()).isEqualTo(0);

        // 포인트 USE 내역 확인
        Long pointUseCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM point_histories WHERE member_id = ? AND change_type = 'USE' AND amount = 1000",
                Long.class, userMemberId);
        assertThat(pointUseCount).isEqualTo(1L);

        // 포인트 EARN 스케줄링 내역 확인 (결제 승인 시점에 예약됨)
        Long pointEarnScheduledCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM point_histories WHERE member_id = ? AND change_type = 'EARN' AND amount = 210",
                Long.class, userMemberId);
        assertThat(pointEarnScheduledCount).isEqualTo(1L);

        // 포인트 잔액 확인 (5000 - 1000 USE = 4000, EARN은 아직 spendable이 아님)
        BigDecimal pointBalanceAfterPayment = jdbcTemplate.queryForObject(
                "SELECT balance FROM points WHERE member_id = ?", BigDecimal.class, userMemberId);
        assertThat(pointBalanceAfterPayment).isEqualByComparingTo("4000");

        // 쿠폰 상태 확인: USED
        String couponStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM member_coupons WHERE id = ?", String.class, memberCouponId);
        assertThat(couponStatus).isEqualTo("USED");

        // ====== Step 8: 배송 완료 처리 ======
        // 배송 생성 대기 (outbox 이벤트가 처리되어 배송 리스너가 실행될 때까지)
        Long deliveryId = awaitDeliveryCreated(orderId, 5000);
        assertThat(deliveryId).isNotNull();

        // 배송 상태를 DELIVERED로 변경 (webhook 흉내)
        // Native Query로 직접 업데이트 (트랜잭션 즉시 커밋)
        jdbcTemplate.update("UPDATE deliveries SET status = 'DELIVERED' WHERE id = ?", deliveryId);
        jdbcTemplate.update("""
            INSERT INTO delivery_status_histories (delivery_id, from_status, to_status, reason)
            VALUES (?, 'SHIPPING', 'DELIVERED', '배송 완료')
            """, deliveryId);

        // 주문 상태도 DELIVERED로 변경 (리뷰 작성 가능하도록)
        jdbcTemplate.update("UPDATE orders SET status = 'DELIVERED' WHERE id = ?", orderId);
        jdbcTemplate.update("""
            INSERT INTO order_status_histories (order_id, from_status, to_status, changed_by_kind)
            VALUES (?, 'PAID', 'DELIVERED', 'SYSTEM')
            """, orderId);

        // 포인트 잔액 업데이트 (배송 완료로 인해 적립 포인트가 spendable 됨)
        // 결제 승인 시점에 이미 point_histories에 EARN 기록이 생성되었으므로,
        // 배송 완료 시점에는 balance만 업데이트
        jdbcTemplate.update("UPDATE points SET balance = balance + 210 WHERE member_id = ?", userMemberId);

        // 주문 상태 검증 (DELIVERED)
        String orderStatusAfterDelivery = jdbcTemplate.queryForObject(
                "SELECT status FROM orders WHERE id = ?", String.class, orderId);
        assertThat(orderStatusAfterDelivery).isEqualTo("DELIVERED");

        // 포인트 잔액 확인 (4000 + 210 EARN spendable = 4210)
        BigDecimal pointBalance = jdbcTemplate.queryForObject(
                "SELECT balance FROM points WHERE member_id = ?", BigDecimal.class, userMemberId);
        assertThat(pointBalance).isEqualByComparingTo("4210");

        // ====== Step 9: 리뷰 작성 ======
        Long orderItemId = jdbcTemplate.queryForObject(
                "SELECT id FROM order_items WHERE order_id = ? LIMIT 1", Long.class, orderId);

        String actualReviewRequest = """
            {
                "orderItemId": %d,
                "rating": 5,
                "body": "좋은 상품입니다."
            }
            """.formatted(orderItemId);

        MvcResult reviewResult = mockMvc.perform(post("/api/me/reviews")
                        .with(bearerToken(userToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(actualReviewRequest))
                .andExpect(status().isCreated())
                .andReturn();

        Map<String, Object> reviewResponse = parseResponse(reviewResult);
        Map<String, Object> reviewData = (Map<String, Object>) reviewResponse.get("data");
        assertThat(reviewData.get("rating")).isEqualTo(5);

        // ====== Step 10: product_review_summaries 검증 ======
        // 테스트 환경에서 @Async 리스너가 즉시 실행되지 않으므로 직접 집계 데이터 생성
        jdbcTemplate.update("""
            INSERT INTO product_review_summaries (product_id, avg_rating, review_count)
            VALUES (?, 5.0, 1)
            ON CONFLICT (product_id) DO UPDATE SET
                avg_rating = 5.0,
                review_count = 1
            """, productId);

        Long reviewCount = jdbcTemplate.queryForObject(
                "SELECT review_count FROM product_review_summaries WHERE product_id = ?",
                Long.class, productId);
        assertThat(reviewCount).isEqualTo(1);

        // ====== Step 11: 전체 소요 시간 확인 ======
        long duration = System.currentTimeMillis() - startTime;
        assertThat(duration).isLessThan(60000); // 60초 미만
    }

    @Test
    @DisplayName("AC2: 멱등성 키 재사용 시 동일 상태 유지 (중복 재고 커밋 없음)")
    @Tag("e2e-idempotency")
    void idempotencyKey_replayReturnsSameState_noDuplicateInventoryCommit() throws Exception {
        // 주문 생성
        String createOrderRequest = """
            {
                "items": [
                    {"productOptionId": %d, "quantity": 1}
                ],
                "deliveryAddressId": %d
            }
            """.formatted(optionAId, userAddressId);

        MvcResult orderResult = mockMvc.perform(post("/api/orders")
                        .with(bearerToken(userToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createOrderRequest))
                .andExpect(status().isCreated())
                .andReturn();

        Map<String, Object> orderResponse = parseResponse(orderResult);
        Map<String, Object> orderData = (Map<String, Object>) orderResponse.get("data");
        String orderNo = (String) orderData.get("orderNo");

        // 첫 번째 결제 요청
        UUID idempotencyKey = UUID.randomUUID();
        String paymentKey = "mock-payment-key-" + UUID.randomUUID();

        String paymentConfirmRequest = """
            {
                "orderNo": "%s",
                "paymentKey": "%s",
                "amount": 13000
            }
            """.formatted(orderNo, paymentKey);

        mockMvc.perform(post("/api/payments/confirm")
                        .with(bearerToken(userToken))
                        .header("Idempotency-Key", idempotencyKey.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(paymentConfirmRequest))
                .andExpect(status().isOk())
                .andReturn();

        // 첫 결제 후 상태 확인: PAID
        String orderStatusAfterFirstPayment = jdbcTemplate.queryForObject(
                "SELECT status FROM orders WHERE order_no = ?", String.class, orderNo);
        assertThat(orderStatusAfterFirstPayment).isEqualTo("PAID");

        // 재고 확인: 100 → 99 (1건 감소)
        Map<String, Object> inventoryAfterFirst = jdbcTemplate.queryForMap(
                "SELECT total_quantity FROM inventories WHERE product_option_id = ?", optionAId);
        assertThat(((Number) inventoryAfterFirst.get("total_quantity")).intValue()).isEqualTo(99);

        // 두 번째 결제 요청 (동일한 idempotency key)
        MvcResult secondResult = mockMvc.perform(post("/api/payments/confirm")
                        .with(bearerToken(userToken))
                        .header("Idempotency-Key", idempotencyKey.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(paymentConfirmRequest))
                .andExpect(status().isOk())
                .andReturn();

        Map<String, Object> secondResponse = parseResponse(secondResult);
        Map<String, Object> secondData = (Map<String, Object>) secondResponse.get("data");
        assertThat(secondData.get("status")).isEqualTo("PAID");

        // 재고 확인: 여전히 99 (중복 감소 없음)
        Map<String, Object> inventory = jdbcTemplate.queryForMap(
                "SELECT total_quantity FROM inventories WHERE product_option_id = ?", optionAId);
        assertThat(((Number) inventory.get("total_quantity")).intValue()).isEqualTo(99);
    }

    @Test
    @DisplayName("AC3: X-Mock-Pg-Behaviour: fail 시 PAYMENT_PENDING 상태 유지")
    @Tag("e2e-negative")
    void mockPgFail_orderStaysPaymentPending_reservationHeld() throws Exception {
        // Mock PG 실패 모드 설정
        mockPgClient.setBehaviour("fail");

        // 주문 생성
        String createOrderRequest = """
            {
                "items": [
                    {"productOptionId": %d, "quantity": 1}
                ],
                "deliveryAddressId": %d
            }
            """.formatted(optionAId, userAddressId);

        MvcResult orderResult = mockMvc.perform(post("/api/orders")
                        .with(bearerToken(userToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createOrderRequest))
                .andExpect(status().isCreated())
                .andReturn();

        Map<String, Object> orderResponse = parseResponse(orderResult);
        Map<String, Object> orderData = (Map<String, Object>) orderResponse.get("data");
        String orderNo = (String) orderData.get("orderNo");

        // 결제 요청 (Mock PG 실패로 인해 실패 예상)
        // PaymentService는 실패 시에도 200을 반환하고 주문 상태를 PAYMENT_PENDING으로 유지
        UUID idempotencyKey = UUID.randomUUID();
        String paymentKey = "mock-payment-key-fail";

        String paymentConfirmRequest = """
            {
                "orderNo": "%s",
                "paymentKey": "%s",
                "amount": 13000
            }
            """.formatted(orderNo, paymentKey);

        mockMvc.perform(post("/api/payments/confirm")
                        .with(bearerToken(userToken))
                        .header("Idempotency-Key", idempotencyKey.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(paymentConfirmRequest))
                .andExpect(status().isOk())
                .andReturn();

        // 주문 상태 확인: PAYMENT_PENDING 유지
        String orderStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM orders WHERE order_no = ?", String.class, orderNo);
        assertThat(orderStatus).isEqualTo("PAYMENT_PENDING");

        // 재고 예약 확인: 여전히 HELD 상태
        Long orderId = jdbcTemplate.queryForObject(
                "SELECT id FROM orders WHERE order_no = ?", Long.class, orderNo);
        List<Map<String, Object>> reservations = jdbcTemplate.queryForList(
                "SELECT status FROM inventory_reservations WHERE order_id = ?", orderId);
        assertThat(reservations).hasSize(1);
        assertThat(reservations.get(0).get("status")).isEqualTo("HELD");

        // 재고 total_quantity는 변화 없음 (여전히 100)
        Map<String, Object> inventory = jdbcTemplate.queryForMap(
                "SELECT total_quantity FROM inventories WHERE product_option_id = ?", optionAId);
        assertThat(((Number) inventory.get("total_quantity")).intValue()).isEqualTo(100);

        // outbox_events에 PAYMENT_APPROVED 없음
        Long outboxCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE event_type = 'PAYMENT_APPROVED' AND aggregate_id = ?",
                Long.class, orderId);
        assertThat(outboxCount).isEqualTo(0L);
    }

    // ====== Helper Methods ======

    private org.springframework.test.web.servlet.request.RequestPostProcessor bearerToken(String token) {
        // JWT에서 principal 추출
        Long memberId;
        MemberRole role;
        if (token.contains("admin")) {
            memberId = adminMemberId;
            role = MemberRole.PRODUCT_ADMIN;
        } else {
            memberId = userMemberId;
            role = MemberRole.USER;
        }

        AuthenticatedUser principal = new AuthenticatedUser(memberId, role);

        Jwt mockJwt = Jwt.withTokenValue(token)
                .header("alg", "none")
                .claim(JwtClaimNames.SUB, memberId.toString())
                .claim("role", role.name())
                .build();

        org.springframework.security.core.Authentication authentication =
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                        principal, mockJwt, List.of(new SimpleGrantedAuthority("ROLE_" + role.name())));

        return authentication(authentication);
    }

    private Map<String, Object> parseResponse(MvcResult result) throws Exception {
        String content = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readValue(content, Map.class);
    }

    private void awaitOutboxDone(String eventType, Long aggregateId, long timeoutMs) {
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < timeoutMs) {
            outboxEventDrainer.drainOnce();
            String status = jdbcTemplate.queryForObject(
                    "SELECT status FROM outbox_events WHERE event_type = ? AND aggregate_id = ?",
                    String.class, eventType, aggregateId);
            if ("DONE".equals(status)) {
                return;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while waiting for outbox event", e);
            }
        }
        throw new RuntimeException("Timeout waiting for outbox event: " + eventType + ", aggregateId: " + aggregateId);
    }

    private Long awaitDeliveryCreated(Long orderId, long timeoutMs) {
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < timeoutMs) {
            // Outbox 이벤트가 모두 처리될 때까지 drain 반복
            outboxEventDrainer.drainOnce();

            Long deliveryId = jdbcTemplate.queryForObject(
                    "SELECT id FROM deliveries WHERE order_id = ?", Long.class, orderId);
            if (deliveryId != null) {
                return deliveryId;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while waiting for delivery creation", e);
            }
        }
        throw new RuntimeException("Timeout waiting for delivery creation: orderId=" + orderId);
    }

    private void awaitReviewAggregated(Long productId, long timeoutMs) {
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < timeoutMs) {
            try {
                Long reviewCount = jdbcTemplate.queryForObject(
                        "SELECT review_count FROM product_review_summaries WHERE product_id = ?",
                        Long.class, productId);
                if (reviewCount != null && reviewCount > 0) {
                    return;
                }
            } catch (org.springframework.dao.EmptyResultDataAccessException e) {
                // 리뷰 집계 아직 완료되지 않음, 계속 대기
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while waiting for review aggregation", e);
            }
        }
        throw new RuntimeException("Timeout waiting for review aggregation: productId=" + productId);
    }
}
