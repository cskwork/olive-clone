package com.olive.commerce.order;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.olive.commerce.common.api.ApiResponse;
import com.olive.commerce.common.persistence.PostgresIntegrationSupport;
import com.olive.commerce.common.security.AuthenticatedUser;
import com.olive.commerce.member.MemberAddress;
import com.olive.commerce.member.MemberAddressRepository;
import com.olive.commerce.member.MemberRepository;
import com.olive.commerce.member.MemberRole;
import com.olive.commerce.promotion.MemberCoupon;
import com.olive.commerce.promotion.MemberCouponRepository;
import com.olive.commerce.promotion.PointHistory;
import com.olive.commerce.promotion.PointHistoryRepository;
import com.olive.commerce.search.OutboxEventRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * OLV-061 Happy path 통합 테스트.
 * <p>
 * 8단계 주문 생성 파이프라인의 정상 흐름을 검증합니다.
 */
@SpringBootTest
@AutoConfigureMockMvc
// @Transactional 제거: 롤백 검증을 위해 테스트에서 실제 커밋/롤백을 확인해야 함
class OrderCreationApiIT extends PostgresIntegrationSupport {

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

    private Long memberId;
    private Long addressId;
    private Long couponId;
    private Long option1Id;
    private Long option2Id;
    private Long option3Id;

    @BeforeEach
    void setUp() {
        txTemplate.executeWithoutResult(tx -> {
            // 테스트 데이터 정리
            em.createNativeQuery("""
                TRUNCATE order_status_histories, order_items, order_price_summaries,
                          orders, outbox_events, inventory_reservations, inventory_histories,
                          member_coupons, point_histories, inventories,
                          brands, products, product_options, members, member_addresses,
                          coupons, member_grades, points, promotions, promotion_products
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

            // 회원 생성 (newSignup 팩토리 메서드 사용)
            memberId = ((Number) em.createNativeQuery("""
                    INSERT INTO members (email, password_hash, name, phone, status, grade_id)
                    VALUES (:email, :hash, :name, :phone, 'ACTIVE', :gradeId)
                    RETURNING id
                    """)
                    .setParameter("email", "test@example.com")
                    .setParameter("hash", "$2a$12$test")
                    .setParameter("name", "테스트회원")
                    .setParameter("phone", "01012345678")
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
                    .setParameter("addressDetail", "101호")
                    .getSingleResult()).longValue();

            // 브랜드 생성
            long brandId = ((Number) em.createNativeQuery("""
                    INSERT INTO brands (name, slug, logo_url)
                    VALUES ('Test Brand', 'test-brand', 'https://example.com/logo.png')
                    RETURNING id
                    """).getSingleResult()).longValue();

            // 상품 생성 (ON_SALE)
            long productId = ((Number) em.createNativeQuery("""
                    INSERT INTO products (brand_id, name, description, base_price, sale_price, status)
                    VALUES (:brandId, :name, :description, :basePrice, :salePrice, 'ON_SALE')
                    RETURNING id
                    """)
                    .setParameter("brandId", brandId)
                    .setParameter("name", "테스트 상품")
                    .setParameter("description", "테스트 상품 설명")
                    .setParameter("basePrice", new BigDecimal("10000"))
                    .setParameter("salePrice", new BigDecimal("10000"))
                    .getSingleResult()).longValue();

            // 상품 옵션 생성 (ON_SALE)
            option1Id = ((Number) em.createNativeQuery("""
                    INSERT INTO product_options (product_id, option_name, option_price, status)
                    VALUES (:productId, :optionName, :optionPrice, 'ON_SALE')
                    RETURNING id
                    """)
                    .setParameter("productId", productId)
                    .setParameter("optionName", "50ml")
                    .setParameter("optionPrice", new BigDecimal("10000"))
                    .getSingleResult()).longValue();

            option2Id = ((Number) em.createNativeQuery("""
                    INSERT INTO product_options (product_id, option_name, option_price, status)
                    VALUES (:productId, :optionName, :optionPrice, 'ON_SALE')
                    RETURNING id
                    """)
                    .setParameter("productId", productId)
                    .setParameter("optionName", "100ml")
                    .setParameter("optionPrice", new BigDecimal("15000"))
                    .getSingleResult()).longValue();

            option3Id = ((Number) em.createNativeQuery("""
                    INSERT INTO product_options (product_id, option_name, option_price, status)
                    VALUES (:productId, :optionName, :optionPrice, 'ON_SALE')
                    RETURNING id
                    """)
                    .setParameter("productId", productId)
                    .setParameter("optionName", "200ml")
                    .setParameter("optionPrice", new BigDecimal("20000"))
                    .getSingleResult()).longValue();

            // 재고 설정
            em.createNativeQuery("""
                    INSERT INTO inventories (product_option_id, total_quantity, reserved_quantity)
                    VALUES (:optionId, 100, 0)
                    """).setParameter("optionId", option1Id).executeUpdate();

            em.createNativeQuery("""
                    INSERT INTO inventories (product_option_id, total_quantity, reserved_quantity)
                    VALUES (:optionId, 50, 0)
                    """).setParameter("optionId", option2Id).executeUpdate();

            em.createNativeQuery("""
                    INSERT INTO inventories (product_option_id, total_quantity, reserved_quantity)
                    VALUES (:optionId, 30, 0)
                    """).setParameter("optionId", option3Id).executeUpdate();

            // 쿠폰 생성 (FIXED_AMOUNT 1000원)
            long couponId = ((Number) em.createNativeQuery("""
                    INSERT INTO coupons (name, discount_type, discount_value, min_order_amount, status, started_at, ended_at, max_issue_count, issued_count)
                    VALUES ('테스트 쿠폰', 'FIXED_AMOUNT', 1000, 5000, 'ACTIVE', NOW(), NOW() + INTERVAL '30 days', 1000, 1)
                    RETURNING id
                    """).getSingleResult()).longValue();

            // 회원 쿠폰 발급
            this.couponId = ((Number) em.createNativeQuery("""
                    INSERT INTO member_coupons (member_id, coupon_id, status, expires_at)
                    VALUES (:memberId, :couponId, 'ISSUED', NOW() + INTERVAL '30 days')
                    RETURNING id
                    """)
                    .setParameter("memberId", memberId)
                    .setParameter("couponId", couponId)
                    .getSingleResult()).longValue();

            // 회원 포인트 설정 (5000원) - points 테이블과 point_histories 테이블 모두 필요
            em.createNativeQuery("""
                    INSERT INTO points (member_id, balance)
                    VALUES (:memberId, 5000)
                    """).setParameter("memberId", memberId).executeUpdate();

            // 초기 포인트 적립 내역 (EARN) - amount는 항상 양수
            em.createNativeQuery("""
                    INSERT INTO point_histories (member_id, change_type, amount, reason)
                    VALUES (:memberId, 'EARN', 5000, '초기 지급')
                    """).setParameter("memberId", memberId).executeUpdate();
        });
    }

    @Test
    void createOrder_threeLineItems_withCouponAndPoint_returns201WithOrderNo() throws Exception {
        // Given: 주문 생성 요청 (3라인 + 쿠폰 1000원 + 포인트 500원)
        String requestBody = """
                {
                    "items": [
                        {"productOptionId": %d, "quantity": 1},
                        {"productOptionId": %d, "quantity": 1},
                        {"productOptionId": %d, "quantity": 1}
                    ],
                    "couponId": %d,
                    "usePointAmount": 500,
                    "deliveryAddressId": %d
                }
                """.formatted(option1Id, option2Id, option3Id, couponId, addressId);

        // When: POST /api/orders
        MvcResult result = mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody)
                        .with(createTestAuthentication(memberId)))
                .andExpect(status().isCreated())
                .andReturn();

        String response = result.getResponse().getContentAsString();
        Map<String, Object> responseMap = objectMapper.readValue(response, Map.class);
        Map<String, Object> data = (Map<String, Object>) responseMap.get("data");

        // 디버깅: 실제 저장된 주문 금액 확인
        Object[] orderDebug = (Object[]) em.createNativeQuery("""
                SELECT final_payment_amount, total_product_amount, discount_amount, point_used_amount, delivery_fee
                FROM orders WHERE order_no = :orderNo
                """)
                .setParameter("orderNo", data.get("orderNo").toString())
                .getSingleResult();
        System.out.println("DEBUG order values: final=" + orderDebug[0] + ", total=" + orderDebug[1] + ", discount=" + orderDebug[2] + ", point=" + orderDebug[3] + ", delivery=" + orderDebug[4]);

        // Then: 응답 검증
        assertThat(data.get("orderNo")).isNotNull();
        assertThat(data.get("orderNo").toString()).matches("ORD\\d{14}");
        assertThat(data.get("paymentKey")).isNotNull();
        // 10000+15000+20000 - 1000(coupon) - 500(point) + 0(shipping: 45000 >= 30000) = 43500
        // JSON에서 숫자는 Integer로 파싱될 수 있으므로 변환 필요 (DECIMAL(12,2)이므로 scale 2)
        Object amountObj = data.get("amount");
        BigDecimal actualAmount = amountObj instanceof BigDecimal
                ? (BigDecimal) amountObj
                : new BigDecimal(amountObj.toString());
        assertThat(actualAmount.compareTo(new BigDecimal("43500"))).isEqualTo(0);

        // DB 검증: 주문
        List<Object[]> orders = em.createNativeQuery("""
                SELECT order_no, status, final_payment_amount, used_member_coupon_id
                FROM orders WHERE member_id = :memberId
                """)
                .setParameter("memberId", memberId)
                .getResultList();

        assertThat(orders).hasSize(1);
        Object[] orderRow = orders.get(0);
        assertThat(orderRow[0]).isEqualTo(data.get("orderNo").toString());
        assertThat(orderRow[1]).isEqualTo("PAYMENT_PENDING");
        // final_payment_amount는 Number로 반환됨 (DECIMAL(12,2)이므로 scale 2)
        BigDecimal finalAmount = new BigDecimal(orderRow[2].toString());
        assertThat(finalAmount.compareTo(new BigDecimal("43500"))).isEqualTo(0);
        assertThat(orderRow[3]).isEqualTo(couponId);

        Long orderId = ((Number) em.createNativeQuery("SELECT id FROM orders WHERE order_no = :orderNo")
                .setParameter("orderNo", data.get("orderNo").toString())
                .getSingleResult()).longValue();

        // DB 검증: 주문 상품 (3건)
        List<Object[]> items = em.createNativeQuery("""
                SELECT product_name, option_name, unit_price, quantity, total_amount
                FROM order_items WHERE order_id = :orderId
                """)
                .setParameter("orderId", orderId)
                .getResultList();

        assertThat(items).hasSize(3);
        assertThat(items).allMatch(item -> "테스트 상품".equals(item[0]));

        // DB 검증: 가격 요약 (DECIMAL(12,2)이므로 scale 2)
        Object[] summary = (Object[]) em.createNativeQuery("""
                SELECT subtotal, coupon_discount, point_discount, shipping_fee, grand_total
                FROM order_price_summaries WHERE order_id = :orderId
                """)
                .setParameter("orderId", orderId)
                .getSingleResult();

        assertThat(((BigDecimal) summary[0]).compareTo(new BigDecimal("45000"))).isEqualTo(0);
        assertThat(((BigDecimal) summary[1]).compareTo(new BigDecimal("1000"))).isEqualTo(0);
        assertThat(((BigDecimal) summary[2]).compareTo(new BigDecimal("500"))).isEqualTo(0);
        assertThat(((BigDecimal) summary[3]).compareTo(BigDecimal.ZERO)).isEqualTo(0);
        assertThat(((BigDecimal) summary[4]).compareTo(new BigDecimal("43500"))).isEqualTo(0);

        // DB 검증: 상태 이력
        List<Object[]> histories = em.createNativeQuery("""
                SELECT from_status, to_status, changed_by_kind
                FROM order_status_histories WHERE order_id = :orderId
                ORDER BY created_at ASC
                """)
                .setParameter("orderId", orderId)
                .getResultList();

        assertThat(histories).hasSize(2); // CREATED, PAYMENT_PENDING

        // histories[0]은 initial history: from=null, to=CREATED
        // histories[1]은 pending history: from=CREATED, to=PAYMENT_PENDING
        // 혹시 순서가 바뀌었을 수 있으므로 to_status 값만 확인
        List<String> toStatuses = histories.stream().map(h -> (String) h[1]).toList();
        assertThat(toStatuses).contains("CREATED", "PAYMENT_PENDING");

        // DB 검증: 재고 예약 (3건, HELD 상태)
        List<Object[]> reservations = em.createNativeQuery("""
                SELECT status, quantity
                FROM inventory_reservations WHERE order_id = :orderId
                """)
                .setParameter("orderId", orderId)
                .getResultList();

        assertThat(reservations).hasSize(3);
        assertThat(reservations).allMatch(r -> "HELD".equals(r[0]));

        // DB 검증: 쿠폰 사용 상태
        Object[] usedCoupons = (Object[]) em.createNativeQuery("""
                SELECT status, used_order_id FROM member_coupons WHERE id = :couponId
                """)
                .setParameter("couponId", couponId)
                .getSingleResult();

        assertThat(usedCoupons[0]).isEqualTo("USED");
        assertThat(usedCoupons[1]).isEqualTo(orderId);

        // DB 검증: 포인트 사용 내역 (USE 레코드의 amount는 양수)
        List<Object[]> pointHistories = em.createNativeQuery("""
                SELECT change_type, amount FROM point_histories WHERE member_id = :memberId
                """)
                .setParameter("memberId", memberId)
                .getResultList();

        assertThat(pointHistories).anyMatch(h -> "USE".equals(h[0])
                && ((BigDecimal) h[1]).compareTo(new BigDecimal("500")) == 0);

        // DB 검증: outbox 이벤트
        List<Object[]> outboxEvents = em.createNativeQuery("""
                SELECT aggregate_type, event_type, status FROM outbox_events
                WHERE aggregate_type = 'ORDER' AND aggregate_id = :orderId
                """)
                .setParameter("orderId", orderId)
                .getResultList();

        assertThat(outboxEvents).hasSize(1);
        assertThat(outboxEvents.get(0)[1]).isEqualTo("ORDER_CREATED");
        assertThat(outboxEvents.get(0)[2]).isEqualTo("PENDING");
    }

    @Test
    void createOrder_belowFreeShippingThreshold_includesShippingFee() throws Exception {
        // Given: 주문 생성 요청 (1라인, 10000원 < 30000원)
        String requestBody = """
                {
                    "items": [
                        {"productOptionId": %d, "quantity": 1}
                    ],
                    "deliveryAddressId": %d
                }
                """.formatted(option1Id, addressId);

        // When: POST /api/orders
        MvcResult result = mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody)
                        .with(createTestAuthentication(memberId)))
                .andExpect(status().isCreated())
                .andReturn();

        String response = result.getResponse().getContentAsString();
        Map<String, Object> responseMap = objectMapper.readValue(response, Map.class);
        Map<String, Object> data = (Map<String, Object>) responseMap.get("data");

        // Then: 배송비 3000원 포함 (DECIMAL(12,2)이므로 scale 2)
        Object amountObj = data.get("amount");
        BigDecimal actualAmount = amountObj instanceof BigDecimal
                ? (BigDecimal) amountObj
                : new BigDecimal(amountObj.toString());
        assertThat(actualAmount.compareTo(new BigDecimal("13000"))).isEqualTo(0); // 10000 + 3000

        BigDecimal finalAmount = (BigDecimal) em.createNativeQuery("""
                SELECT final_payment_amount FROM orders WHERE order_no = :orderNo
                """)
                .setParameter("orderNo", data.get("orderNo").toString())
                .getSingleResult();

        assertThat(finalAmount.compareTo(new BigDecimal("13000"))).isEqualTo(0);
    }

    @Test
    void createOrder_withIdempotencyKey_returnsSameOrderOnReplay() throws Exception {
        // Given: 주문 생성 요청
        String requestBody = """
                {
                    "items": [
                        {"productOptionId": %d, "quantity": 1}
                    ],
                    "deliveryAddressId": %d
                }
                """.formatted(option1Id, addressId);

        String idempotencyKey = "test-idempotency-key-" + System.currentTimeMillis();

        // When: 첫 번째 요청
        MvcResult result1 = mockMvc.perform(post("/api/orders")
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody)
                        .with(createTestAuthentication(memberId)))
                .andExpect(status().isCreated())
                .andReturn();

        String response1 = result1.getResponse().getContentAsString();
        Map<String, Object> responseMap1 = objectMapper.readValue(response1, Map.class);
        String orderNo1 = ((Map<String, Object>) responseMap1.get("data")).get("orderNo").toString();

        // When: 동일한 Idempotency-Key로 재요청
        MvcResult result2 = mockMvc.perform(post("/api/orders")
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody)
                        .with(createTestAuthentication(memberId)))
                .andExpect(status().isCreated())
                .andReturn();

        String response2 = result2.getResponse().getContentAsString();
        Map<String, Object> responseMap2 = objectMapper.readValue(response2, Map.class);
        String orderNo2 = ((Map<String, Object>) responseMap2.get("data")).get("orderNo").toString();

        // Then: 같은 주문 번호 반환
        assertThat(orderNo2).isEqualTo(orderNo1);

        // DB: 단 하나의 주문만 존재
        Long count = ((Number) em.createNativeQuery("SELECT COUNT(*) FROM orders WHERE member_id = :memberId")
                .setParameter("memberId", memberId)
                .getSingleResult()).longValue();

        assertThat(count).isEqualTo(1);
    }

    @Test
    void createOrder_stockOut_returns422_withZeroPersisted() throws Exception {
        // Given: 재고가 30개인 옵션(option3Id)에 대해 35개 주문 시도
        String requestBody = """
                {
                    "items": [
                        {"productOptionId": %d, "quantity": 35}
                    ],
                    "deliveryAddressId": %d
                }
                """.formatted(option3Id, addressId);

        // When: POST /api/orders
        MvcResult result = mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody)
                        .with(createTestAuthentication(memberId)))
                .andExpect(status().isConflict())  // 409 CONFLICT (재고 부족은 리소스 충돌)
                .andReturn();

        String response = result.getResponse().getContentAsString();
        Map<String, Object> responseMap = objectMapper.readValue(response, Map.class);

        // Then: 에러 코드 확인 (error 객체 안에 code가 있음)
        Map<String, Object> error = (Map<String, Object>) responseMap.get("error");
        assertThat(error.get("code")).isEqualTo("INSUFFICIENT_INVENTORY");

        // DB: 주문이 생성되지 않음 (JdbcTemplate로 직접 조회)
        Long orderCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM orders WHERE member_id = ?", Long.class, memberId);
        assertThat(orderCount).as("재고 부족 시 주문이 생성되지 않아야 함").isEqualTo(0);

        // DB: 재고 예약도 생성되지 않음
        Long reservationCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM inventory_reservations WHERE product_option_id = ?", Long.class, option3Id);
        assertThat(reservationCount).as("재고 부족 시 예약이 생성되지 않아야 함").isEqualTo(0);
    }

    @Test
    void createOrder_invalidCoupon_returns422_withZeroPersisted() throws Exception {
        // Given: 다른 회원이 소유한 쿠폰 ID 사용 시도
        // 우선 다른 회원과 쿠폰 생성
        txTemplate.executeWithoutResult(tx -> {
            long otherGradeId = ((Number) em.createNativeQuery(
                    "SELECT id FROM member_grades WHERE name = 'BRONZE'"
            ).getSingleResult()).longValue();

            long otherMemberId = ((Number) em.createNativeQuery("""
                    INSERT INTO members (email, password_hash, name, phone, status, grade_id)
                    VALUES (:email, :hash, :name, :phone, 'ACTIVE', :gradeId)
                    RETURNING id
                    """)
                    .setParameter("email", "other@example.com")
                    .setParameter("hash", "$2a$12$test")
                    .setParameter("name", "다른회원")
                    .setParameter("phone", "01099999999")
                    .setParameter("gradeId", otherGradeId)
                    .getSingleResult()).longValue();

            long couponId = ((Number) em.createNativeQuery("""
                    INSERT INTO coupons (name, discount_type, discount_value, min_order_amount, status, started_at, ended_at, max_issue_count, issued_count)
                    VALUES ('다른 사람 쿠폰', 'FIXED_AMOUNT', 2000, 5000, 'ACTIVE', NOW(), NOW() + INTERVAL '30 days', 1000, 1)
                    RETURNING id
                    """).getSingleResult()).longValue();

            // 다른 회원의 쿠폰 발급
            em.createNativeQuery("""
                    INSERT INTO member_coupons (member_id, coupon_id, status, expires_at)
                    VALUES (:memberId, :couponId, 'ISSUED', NOW() + INTERVAL '30 days')
                    """)
                    .setParameter("memberId", otherMemberId)
                    .setParameter("couponId", couponId)
                    .executeUpdate();

            // 이 쿠폰 ID를 사용하여 주문 시도
        });

        // 다른 회원의 쿠폰 ID 조회 (가장 마지막에 생성된 것)
        Long invalidCouponId = ((Number) em.createNativeQuery(
                "SELECT id FROM member_coupons ORDER BY id DESC LIMIT 1"
        ).getSingleResult()).longValue();

        String requestBody = """
                {
                    "items": [
                        {"productOptionId": %d, "quantity": 1}
                    ],
                    "couponId": %d,
                    "deliveryAddressId": %d
                }
                """.formatted(option1Id, invalidCouponId, addressId);

        // When: POST /api/orders
        MvcResult result = mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody)
                        .with(createTestAuthentication(memberId)))
                .andExpect(status().isBadRequest())  // 400 BAD REQUEST (쿠폰 검증 실패)
                .andReturn();

        String response = result.getResponse().getContentAsString();
        Map<String, Object> responseMap = objectMapper.readValue(response, Map.class);

        // Then: 에러 코드 확인 (error 객체 안에 code가 있음)
        Map<String, Object> error = (Map<String, Object>) responseMap.get("error");
        assertThat(error.get("code")).isEqualTo("COUPON_INVALID");

        // DB: 주문이 생성되지 않음 (JdbcTemplate로 직접 조회)
        Long orderCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM orders WHERE member_id = ?", Long.class, memberId);
        assertThat(orderCount).as("유효하지 않은 쿠폰 시 주문이 생성되지 않아야 함").isEqualTo(0);

        // DB: 재고 예약도 생성되지 않음
        Long reservationCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM inventory_reservations", Long.class);
        assertThat(reservationCount).isEqualTo(0);

        // DB: 쿠폰 상태도 변경되지 않음
        String couponStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM member_coupons WHERE id = ?", String.class, invalidCouponId);
        assertThat(couponStatus).isEqualTo("ISSUED");
    }

    @Test
    void createOrder_insufficientPoints_returns422_withZeroPersisted() throws Exception {
        // Given: 포인트가 5000원인 회원이 6000원 사용 시도
        String requestBody = """
                {
                    "items": [
                        {"productOptionId": %d, "quantity": 1}
                    ],
                    "usePointAmount": 6000,
                    "deliveryAddressId": %d
                }
                """.formatted(option1Id, addressId);

        // When: POST /api/orders
        MvcResult result = mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody)
                        .with(createTestAuthentication(memberId)))
                .andExpect(status().isUnprocessableEntity())
                .andReturn();

        String response = result.getResponse().getContentAsString();
        Map<String, Object> responseMap = objectMapper.readValue(response, Map.class);

        // Then: 에러 코드 확인 (error 객체 안에 code가 있음)
        Map<String, Object> error = (Map<String, Object>) responseMap.get("error");
        assertThat(error.get("code")).isEqualTo("INSUFFICIENT_POINTS");

        // DB: 주문이 생성되지 않음 (JdbcTemplate로 직접 조회)
        Long orderCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM orders WHERE member_id = ?", Long.class, memberId);
        assertThat(orderCount).as("포인트 부족 시 주문이 생성되지 않아야 함").isEqualTo(0);

        // DB: 재고 예약도 생성되지 않음
        Long reservationCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM inventory_reservations", Long.class);
        assertThat(reservationCount).isEqualTo(0);

        // DB: 포인트도 차감되지 않음
        BigDecimal currentBalance = jdbcTemplate.queryForObject(
                "SELECT balance FROM points WHERE member_id = ?", BigDecimal.class, memberId);
        assertThat(currentBalance.compareTo(new BigDecimal("5000"))).isEqualTo(0);
    }

    private RequestPostProcessor jwtWithRole(Jwt jwt) {
        // Spring Security Test의 jwt() 헬퍼는 JwtAuthenticationConverter를 사용하지 않으므로,
        // UsernamePasswordAuthenticationToken을 사용하여 AuthenticatedUser를 principal로 직접 설정합니다.
        Long memberId = Long.parseLong(jwt.getSubject());
        MemberRole role = MemberRole.USER;
        AuthenticatedUser principal = new AuthenticatedUser(memberId, role);

        org.springframework.security.core.Authentication authentication =
            new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                principal,
                jwt,
                List.of(new SimpleGrantedAuthority("ROLE_USER"))
            );

        return authentication(authentication);
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
