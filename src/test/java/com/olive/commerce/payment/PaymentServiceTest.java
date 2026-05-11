package com.olive.commerce.payment;

import com.olive.commerce.common.audit.AuditLogger;
import com.olive.commerce.common.error.ErrorCode;
import com.olive.commerce.common.persistence.PostgresIntegrationSupport;
import com.olive.commerce.inventory.InventoryService;
import com.olive.commerce.member.Member;
import com.olive.commerce.member.MemberAddress;
import com.olive.commerce.member.MemberAddressRepository;
import com.olive.commerce.member.MemberGradeRepository;
import com.olive.commerce.member.MemberRepository;
import com.olive.commerce.order.Order;
import com.olive.commerce.order.OrderRepository;
import com.olive.commerce.payment.client.PgClient;
import com.olive.commerce.payment.client.dto.ConfirmResponse;
import com.olive.commerce.promotion.CouponService;
import com.olive.commerce.promotion.PointService;
import com.olive.commerce.search.OutboxEventRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.springframework.transaction.annotation.Transactional;

/**
 * OLV-072 PaymentService 단위 테스트.
 * <p>
 * Service 레이어 테스트로, MockMvc를 통한 API 호출 없이 로직만 검증.
 * <p>
 * 각 테스트는 독립적으로 데이터를 생성하고 정리합니다.
 */
@SpringBootTest
@Transactional
class PaymentServiceTest extends PostgresIntegrationSupport {

    private static JdbcTemplate staticJdbcTemplate;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private MemberAddressRepository memberAddressRepository;

    @Autowired
    private MemberGradeRepository memberGradeRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManager entityManager;

    @MockBean
    private InventoryService inventoryService;

    @MockBean
    private CouponService couponService;

    @MockBean
    private PointService pointService;

    @MockBean
    private PgClient pgClient;

    @MockBean
    private OutboxEventRepository outboxEventRepository;

    @MockBean
    private AuditLogger auditLogger;

    @MockBean
    private ApplicationEventPublisher eventPublisher;

    private String orderNo;
    private Long orderId;
    private Long paymentId;

    @BeforeAll
    static void setUpClass(@Autowired JdbcTemplate jdbcTemplate) {
        staticJdbcTemplate = jdbcTemplate;
        // MemberGrade가 없으면 생성 (모든 테스트에서 공유)
        Integer count = staticJdbcTemplate.queryForObject("SELECT COUNT(*) FROM member_grades", Integer.class);
        if (count == null || count == 0) {
            staticJdbcTemplate.update("""
                INSERT INTO member_grades (name, discount_rate, point_rate, benefit_description, sort_order)
                VALUES
                    ('BRONZE', 0.00, 1.00, '기본 등급', 1),
                    ('SILVER', 2.00, 2.00, '실버 등급', 2),
                    ('GOLD', 5.00, 3.00, '골드 등급', 3)
                """);
        }
    }

    @BeforeEach
    void setUp() {
        // 테이블 정리 (이전 테스트의 데이터 제거)
        jdbcTemplate.execute("""
            TRUNCATE payment_transactions, payments, outbox_events,
                      order_status_histories, orders, member_addresses, members
            RESTART IDENTITY CASCADE
            """);

        // 고유한 orderNo 생성
        orderNo = "TEST-ORD-" + UUID.randomUUID().toString().substring(0, 8);

        // MemberGrade 조회
        Long gradeId = jdbcTemplate.queryForObject(
                "SELECT id FROM member_grades WHERE name = 'BRONZE'", Long.class);

        // Member 생성
        String uniqueEmail = "payment-test-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        Long memberId = jdbcTemplate.queryForObject("""
                INSERT INTO members (email, password_hash, name, phone, status, grade_id)
                VALUES (?, ?, ?, ?, 'ACTIVE', ?)
                RETURNING id
                """, Long.class, uniqueEmail, "$2a$12$test",
                "결제테스트", "01012345678", gradeId);

        // MemberAddress 생성
        Long addressId = jdbcTemplate.queryForObject("""
                INSERT INTO member_addresses (member_id, recipient_name, phone, zipcode, address_main, address_detail, is_default)
                VALUES (?, ?, ?, ?, ?, ?, true)
                RETURNING id
                """, Long.class, memberId, "홍길동", "01012345678",
                "12345", "서울시 강남구", "101호");

        // Order 생성
        orderId = jdbcTemplate.queryForObject("""
                INSERT INTO orders (member_id, delivery_address_id, order_no, status,
                                   total_product_amount, discount_amount, point_used_amount, delivery_fee, final_payment_amount)
                VALUES (?, ?, ?, 'PAYMENT_PENDING', 50000, 0, 0, 3000, 35000)
                RETURNING id
                """, Long.class, memberId, addressId, orderNo);

        // Payment 생성
        UUID paymentUuid = UUID.randomUUID();
        paymentId = jdbcTemplate.queryForObject("""
                INSERT INTO payments (order_id, method, status, requested_amount, idempotency_key)
                VALUES (?, 'CARD', 'REQUESTED', 35000, ?)
                RETURNING id
                """, Long.class, orderId, paymentUuid);

        // 영속성 컨텍스트와 DB 동기화
        entityManager.flush();
        entityManager.clear();
    }

    @Test
    @DisplayName("Happy path: PAYMENT_PENDING -> PAID, 모든 side effects 완료")
    void happyPath_paymentPending_to_paid() {
        // Given
        UUID idempotencyKey = UUID.randomUUID();
        String paymentKey = "mock-payment-key-123";
        BigDecimal amount = BigDecimal.valueOf(35000);

        PaymentDtos.ConfirmRequest request = new PaymentDtos.ConfirmRequest(
            orderNo, paymentKey, amount);

        // Mock PG client response
        ConfirmResponse pgResponse = new ConfirmResponse(
            "APPROVED",
            LocalDateTime.now(),
            null
        );

        // Mock dependencies
        org.mockito.Mockito.when(pgClient.confirmPayment(org.mockito.Mockito.any()))
            .thenReturn(pgResponse);
        org.mockito.Mockito.doNothing().when(inventoryService).commit(org.mockito.Mockito.anyLong());
        org.mockito.Mockito.doNothing().when(couponService).markUsed(org.mockito.Mockito.anyLong(), org.mockito.Mockito.anyLong());
        org.mockito.Mockito.doNothing().when(pointService).use(org.mockito.Mockito.anyLong(), org.mockito.Mockito.any(), org.mockito.Mockito.anyLong());
        org.mockito.Mockito.doNothing().when(pointService).earnScheduled(
            org.mockito.Mockito.anyLong(), org.mockito.Mockito.any(), org.mockito.Mockito.anyLong(),
            org.mockito.Mockito.any(), org.mockito.Mockito.any()
        );

        // DEBUG: 테스트 메서드에서 직접 order 조회 확인
        var orderFromRepo = orderRepository.findByOrderNo(orderNo);
        System.out.println("DEBUG: orderFromRepo.isPresent() = " + orderFromRepo.isPresent());
        if (orderFromRepo.isPresent()) {
            System.out.println("DEBUG: orderFromRepo.get().getId() = " + orderFromRepo.get().getId());
            System.out.println("DEBUG: orderFromRepo.get().getOrderNo() = " + orderFromRepo.get().getOrderNo());
        }

        // DEBUG: JdbcTemplate으로 직접 조회
        String orderNoFromDb = jdbcTemplate.queryForObject(
                "SELECT order_no FROM orders WHERE id = ?", String.class, orderId);
        System.out.println("DEBUG: orderNoFromDb = " + orderNoFromDb);

        // When
        PaymentDtos.ConfirmResponse response = paymentService.confirmPayment(request, idempotencyKey);

        // Then
        assertThat(response.orderNo()).isEqualTo(orderNo);
        assertThat(response.status()).isEqualTo("PAID");
        assertThat(response.paymentKey()).isEqualTo(paymentKey);

        // Verify order status changed
        String orderStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM orders WHERE id = ?", String.class, orderId);
        assertThat(orderStatus).isEqualTo("PAID");

        // Verify payment status changed
        String paymentStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM payments WHERE id = ?", String.class, paymentId);
        assertThat(paymentStatus).isEqualTo("APPROVED");

        // Verify side effects were called
        org.mockito.Mockito.verify(inventoryService).commit(orderId);
        org.mockito.Mockito.verify(pointService).use(org.mockito.Mockito.anyLong(), org.mockito.Mockito.any(), org.mockito.Mockito.eq(orderId));
    }

    @Test
    @DisplayName("결제 금액 불일치 시 422 반환, 상태 변화 없음")
    void amountMismatch_returns422_noStateChange() {
        // Given
        UUID idempotencyKey = UUID.randomUUID();
        BigDecimal wrongAmount = BigDecimal.valueOf(99999);

        PaymentDtos.ConfirmRequest request = new PaymentDtos.ConfirmRequest(
            orderNo, "pg-key-mismatch", wrongAmount);

        // When & Then
        var exception = assertThrows(com.olive.commerce.common.error.BusinessException.class, () -> {
            paymentService.confirmPayment(request, idempotencyKey);
        });

        assertThat(exception.errorCode()).isEqualTo(ErrorCode.PAYMENT_AMOUNT_MISMATCH);

        // Verify order and payment status unchanged
        String orderStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM orders WHERE id = ?", String.class, orderId);
        assertThat(orderStatus).isEqualTo("PAYMENT_PENDING");

        String paymentStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM payments WHERE id = ?", String.class, paymentId);
        assertThat(paymentStatus).isEqualTo("REQUESTED");

        // Verify PG was NOT called
        org.mockito.Mockito.verify(pgClient, org.mockito.Mockito.never()).confirmPayment(org.mockito.Mockito.any());
    }

    @Test
    @DisplayName("Idempotency-Key로 재요청 시 캐시된 응답 반환, PG 재호출 없음")
    void replayWithSameIdempotencyKey_returnsCachedResponse() {
        // Given
        UUID idempotencyKey = UUID.randomUUID();
        String paymentKey = "mock-payment-key-456";
        BigDecimal amount = BigDecimal.valueOf(35000);

        PaymentDtos.ConfirmRequest request = new PaymentDtos.ConfirmRequest(
            orderNo, paymentKey, amount);

        ConfirmResponse pgResponse = new ConfirmResponse(
            "APPROVED",
            LocalDateTime.now(),
            null
        );

        org.mockito.Mockito.when(pgClient.confirmPayment(org.mockito.Mockito.any()))
            .thenReturn(pgResponse);
        org.mockito.Mockito.doNothing().when(inventoryService).commit(org.mockito.Mockito.anyLong());
        org.mockito.Mockito.doNothing().when(couponService).markUsed(org.mockito.Mockito.anyLong(), org.mockito.Mockito.anyLong());
        org.mockito.Mockito.doNothing().when(pointService).use(org.mockito.Mockito.anyLong(), org.mockito.Mockito.any(), org.mockito.Mockito.anyLong());
        org.mockito.Mockito.doNothing().when(pointService).earnScheduled(
            org.mockito.Mockito.anyLong(), org.mockito.Mockito.any(), org.mockito.Mockito.anyLong(),
            org.mockito.Mockito.any(), org.mockito.Mockito.any()
        );

        // When: 첫 번째 요청
        PaymentDtos.ConfirmResponse response1 = paymentService.confirmPayment(request, idempotencyKey);

        // When: 같은 idempotencyKey로 두 번째 요청
        PaymentDtos.ConfirmResponse response2 = paymentService.confirmPayment(request, idempotencyKey);

        // Then: 같은 응답 반환
        assertThat(response1.orderNo()).isEqualTo(response2.orderNo());
        assertThat(response1.paymentKey()).isEqualTo(response2.paymentKey());

        // Then: PG는 한 번만 호출되어야 함
        org.mockito.Mockito.verify(pgClient, org.mockito.Mockito.times(1)).confirmPayment(org.mockito.Mockito.any());
    }
}
