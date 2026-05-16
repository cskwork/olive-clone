package com.olive.commerce.payment;

import com.olive.commerce.common.audit.AuditLogger;
import com.olive.commerce.common.error.ErrorCode;
import com.olive.commerce.common.persistence.PostgresIntegrationSupport;
import com.olive.commerce.inventory.InventoryService;
import com.olive.commerce.member.Member;
import com.olive.commerce.member.MemberAddress;
import com.olive.commerce.member.MemberGrade;
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
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;

/**
 * OLV-072 PaymentService 단위 테스트.
 * <p>
 * 각 테스트는 @Transactional로 실행되며, 테스트 내에서 데이터를 생성합니다.
 */
@SpringBootTest
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
        // 테이블 정리
        jdbcTemplate.execute("""
            TRUNCATE payment_transactions, payments, outbox_events,
                      order_status_histories, orders, member_addresses, members
            RESTART IDENTITY CASCADE
            """);
    }

    @Test
    @Transactional
    @DisplayName("Happy path: PAYMENT_PENDING -> PAID, 모든 side effects 완료")
    void happyPath_paymentPending_to_paid() {
        // Given: 테스트 데이터 생성
        TestDataSetup dataSetup = createTestData();

        UUID idempotencyKey = UUID.randomUUID();
        String paymentKey = "mock-payment-key-123";
        BigDecimal amount = BigDecimal.valueOf(35000);

        PaymentDtos.ConfirmRequest request = new PaymentDtos.ConfirmRequest(
            dataSetup.orderNo, paymentKey, amount);

        // Mock PG client response
        ConfirmResponse pgResponse = new ConfirmResponse(
            "APPROVED",
            LocalDateTime.now(),
            null
        );

        when(pgClient.confirmPayment(any())).thenReturn(pgResponse);
        doNothing().when(inventoryService).commit(anyLong());
        doNothing().when(couponService).markUsed(anyLong(), anyLong());
        doNothing().when(pointService).use(anyLong(), any(), anyLong());
        doNothing().when(pointService).earnScheduled(
            anyLong(), any(), anyLong(), any(), any()
        );

        // When
        PaymentDtos.ConfirmResponse response = paymentService.confirmPayment(request, idempotencyKey);

        // Then
        assertThat(response.orderNo()).isEqualTo(dataSetup.orderNo);
        assertThat(response.status()).isEqualTo("PAID");
        assertThat(response.paymentKey()).isEqualTo(paymentKey);

        // Verify order status changed (같은 트랜잭션 내에서 조회)
        Order order = orderRepository.findById(dataSetup.orderId).orElseThrow();
        assertThat(order.getStatus().name()).isEqualTo(Order.OrderStatus.PAID.name());

        Payment payment = paymentRepository.findById(dataSetup.paymentId).orElseThrow();
        assertThat(payment.getStatus()).isEqualTo(Payment.PaymentStatus.APPROVED);
    }

    @Test
    @Transactional
    @DisplayName("결제 금액 불일치 시 422 반환, 상태 변화 없음")
    void amountMismatch_returns422_noStateChange() {
        // Given: 테스트 데이터 생성
        TestDataSetup dataSetup = createTestData();

        UUID idempotencyKey = UUID.randomUUID();
        BigDecimal wrongAmount = BigDecimal.valueOf(99999);

        PaymentDtos.ConfirmRequest request = new PaymentDtos.ConfirmRequest(
            dataSetup.orderNo, "pg-key-mismatch", wrongAmount);

        // When & Then
        var exception = assertThrows(com.olive.commerce.common.error.BusinessException.class, () -> {
            paymentService.confirmPayment(request, idempotencyKey);
        });

        assertThat(exception.errorCode()).isEqualTo(ErrorCode.PAYMENT_AMOUNT_MISMATCH);

        // Verify order and payment status unchanged
        Order order = orderRepository.findById(dataSetup.orderId).orElseThrow();
        assertThat(order.getStatus().name()).isEqualTo(Order.OrderStatus.PAYMENT_PENDING.name());

        Payment payment = paymentRepository.findById(dataSetup.paymentId).orElseThrow();
        assertThat(payment.getStatus()).isEqualTo(Payment.PaymentStatus.REQUESTED);
    }

    @Test
    @Transactional
    @DisplayName("Idempotency-Key로 재요청 시 캐시된 응답 반환, PG 재호출 없음")
    void replayWithSameIdempotencyKey_returnsCachedResponse() {
        // Given: 테스트 데이터 생성
        TestDataSetup dataSetup = createTestData();

        UUID idempotencyKey = UUID.randomUUID();
        String paymentKey = "mock-payment-key-456";
        BigDecimal amount = BigDecimal.valueOf(35000);

        PaymentDtos.ConfirmRequest request = new PaymentDtos.ConfirmRequest(
            dataSetup.orderNo, paymentKey, amount);

        ConfirmResponse pgResponse = new ConfirmResponse(
            "APPROVED",
            LocalDateTime.now(),
            null
        );

        when(pgClient.confirmPayment(any())).thenReturn(pgResponse);
        doNothing().when(inventoryService).commit(anyLong());
        doNothing().when(couponService).markUsed(anyLong(), anyLong());
        doNothing().when(pointService).use(anyLong(), any(), anyLong());
        doNothing().when(pointService).earnScheduled(
            anyLong(), any(), anyLong(), any(), any()
        );

        // When: 첫 번째 요청
        PaymentDtos.ConfirmResponse response1 = paymentService.confirmPayment(request, idempotencyKey);

        // When: 같은 idempotencyKey로 두 번째 요청
        PaymentDtos.ConfirmResponse response2 = paymentService.confirmPayment(request, idempotencyKey);

        // Then: 같은 응답 반환
        assertThat(response1.orderNo()).isEqualTo(response2.orderNo());
        assertThat(response1.paymentKey()).isEqualTo(response2.paymentKey());

        // Then: PG는 한 번만 호출되어야 함
        org.mockito.Mockito.verify(pgClient, org.mockito.Mockito.times(1)).confirmPayment(any());
    }

    /**
     * 테스트 데이터 생성 헬퍼 메서드.
     * 같은 트랜잭션 내에서 데이터를 생성합니다.
     */
    private TestDataSetup createTestData() {
        // MemberGrade 조회
        MemberGrade grade = memberGradeRepository.findAll().stream()
                .filter(g -> "BRONZE".equals(g.getName()))
                .findFirst()
                .orElseThrow();

        // Member 생성
        String uniqueEmail = "payment-test-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        Member member = Member.newSignup(uniqueEmail, "$2a$12$test", "결제테스트", "01012345678", grade.getId());
        member = memberRepository.save(member);

        // MemberAddress 생성
        MemberAddress address = MemberAddress.newAddress(
                member.getId(), "홍길동", "01012345678", "12345", "서울시 강남구", "101호", true
        );
        address = memberAddressRepository.save(address);

        // Order 생성 (orderNo는 명시적으로 생성하여 할당)
        String orderNo = "ORD" + System.currentTimeMillis();
        Order order = Order.create(member.getId(), address.getId());
        order.setOrderNo(orderNo);
        order.setPriceDetails(
                BigDecimal.valueOf(50000),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.valueOf(3000),
                BigDecimal.valueOf(35000)
        );
        order.toPaymentPending();
        order = orderRepository.save(order);
        entityManager.flush();

        // 트리거에 의해 생성된 orderNo를 DB에서 읽어옴
        String actualOrderNo = jdbcTemplate.queryForObject(
            "SELECT order_no FROM orders WHERE id = ?", String.class, order.getId());

        // 디버깅: findByOrderNo()가 제대로 작동하는지 확인
        Order foundByNo = orderRepository.findByOrderNo(actualOrderNo).orElse(null);
        if (foundByNo == null) {
            throw new RuntimeException("findByOrderNo() failed! actualOrderNo=" + actualOrderNo);
        }

        // 영속성 컨텍스트 클리어 - 트리거에 의해 변경된 orderNo를 반영
        entityManager.clear();

        // Payment 생성
        UUID paymentUuid = UUID.randomUUID();
        Payment payment = Payment.createRequest(
                order,
                Payment.PaymentMethod.CARD,
                BigDecimal.valueOf(35000),
                paymentUuid
        );
        payment = paymentRepository.save(payment);

        return new TestDataSetup(member.getId(), actualOrderNo, order.getId(), payment.getId());
    }

    /**
     * 테스트 데이터를 담는 레코드.
     */
    private record TestDataSetup(
        Long memberId,
        String orderNo,
        Long orderId,
        Long paymentId
    ) {}
}
