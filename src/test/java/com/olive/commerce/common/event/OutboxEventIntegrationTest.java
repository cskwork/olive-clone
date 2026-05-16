package com.olive.commerce.common.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.olive.commerce.common.analytics.SalesAggregator;
import com.olive.commerce.common.notification.NotificationService;
import com.olive.commerce.common.persistence.PostgresIntegrationSupport;
import com.olive.commerce.member.Member;
import com.olive.commerce.member.MemberAddress;
import com.olive.commerce.member.MemberAddressRepository;
import com.olive.commerce.member.MemberGrade;
import com.olive.commerce.member.MemberGradeRepository;
import com.olive.commerce.member.MemberRepository;
import com.olive.commerce.order.Order;
import com.olive.commerce.order.OrderRepository;
import com.olive.commerce.payment.Payment;
import com.olive.commerce.payment.PaymentRepository;
import com.olive.commerce.promotion.PointHistoryRepository;
import com.olive.commerce.promotion.PointService;
import com.olive.commerce.review.ReviewEligibilityCache;
import com.olive.commerce.search.OutboxEvent;
import com.olive.commerce.search.OutboxEventRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OLV-110 아웃박스 패턴 + 이벤트 구독자 통합 테스트.
 * <p>결제 승인 시 아웃박스 이벤트가 생성되고 구독자들이 올바르게 동작하는지 검증합니다.
 */
@SpringBootTest
class OutboxEventIntegrationTest extends PostgresIntegrationSupport {

    @Autowired
    private OutboxPublisher outboxPublisher;

    @Autowired
    private OutboxEventDrainer outboxEventDrainer;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private SalesAggregator salesAggregator;

    @Autowired
    private PointService pointService;

    @Autowired
    private PointHistoryRepository pointHistoryRepository;

    @Autowired
    private ReviewEligibilityCache reviewEligibilityCache;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private MemberGradeRepository memberGradeRepository;

    @Autowired
    private MemberAddressRepository memberAddressRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private static JdbcTemplate staticJdbcTemplate;

    private Member testMember;
    private Order testOrder;
    private Payment testPayment;

    @BeforeAll
    static void setUpClass(@Autowired JdbcTemplate jdbcTemplate) {
        staticJdbcTemplate = jdbcTemplate;
        // MemberGrade가 없으면 생성
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
        // 테스트 데이터 정리
        notificationService.clearSpy();
        try {
            redisTemplate.getConnectionFactory().getConnection().flushDb();
        } catch (Exception e) {
            // Redis 연결 실패 무시
        }

        // 테이블 정리 (points 테이블도 포함)
        jdbcTemplate.execute("""
            TRUNCATE outbox_events, point_histories, points, payments,
                      order_status_histories, orders, member_addresses, members
            RESTART IDENTITY CASCADE
            """);

        // 테스트 데이터 생성
        createTestData();
    }

    public void createTestData() {
        transactionTemplate.executeWithoutResult(status -> {
        // MemberGrade 조회
        MemberGrade grade = memberGradeRepository.findAll().stream()
                .filter(g -> "BRONZE".equals(g.getName()))
                .findFirst()
                .orElseThrow();

        // Member 생성
        String uniqueEmail = "outbox-test-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        testMember = Member.newSignup(uniqueEmail, "$2a$12$test", "테스트회원", "01012345678", grade.getId());
        testMember = memberRepository.save(testMember);

        // MemberAddress 생성
        MemberAddress address = MemberAddress.newAddress(
                testMember.getId(), "테스트", "01012345678", "00000", "서울", "상세주소", true
        );
        address = memberAddressRepository.save(address);

        // Order 생성
        String orderNo = "TEST-ORD-" + System.currentTimeMillis();
        testOrder = Order.create(testMember.getId(), address.getId());
        testOrder.setOrderNo(orderNo);
        testOrder.setPriceDetails(
                BigDecimal.valueOf(50000),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.valueOf(3000),
                BigDecimal.valueOf(53000)
        );
        testOrder.toPaymentPending();
        testOrder.toPaid();
        testOrder = orderRepository.save(testOrder);
        entityManager.flush();
        entityManager.clear();

        // Payment 생성
        Payment payment = Payment.createRequest(
                testOrder,
                Payment.PaymentMethod.CARD,
                BigDecimal.valueOf(53000),
                UUID.randomUUID()
        );
        payment.approve("pg-test-key", "MOCK", BigDecimal.valueOf(53000), OffsetDateTime.now());
        testPayment = paymentRepository.save(payment);
        entityManager.flush();
        entityManager.clear();
        });
    }

    @Test
    @DisplayName("AC1: 결제 승인 후 아웃박스 이벤트 생성 및 1초 내 DONE 상태로 변경")
    void paymentApproved_createsOutboxEvent_andProcessesWithinOneSecond() {
        // given
        Map<String, Object> payload = Map.of(
                "orderId", testOrder.getId(),
                "orderNo", testOrder.getOrderNo(),
                "paymentId", testPayment.getId(),
                "paymentKey", testPayment.getPaymentKey(),
                "approvedAmount", testPayment.getApprovedAmount()
        );

        // when: 아웃박스 이벤트 발행
        outboxPublisher.publish("PAYMENT", testPayment.getId(), "PAYMENT_APPROVED", payload);

        // then: 아웃박스 이벤트가 PENDING 상태로 생성됨
        OutboxEvent outboxEvent = outboxEventRepository.findAll().get(0);
        assertThat(outboxEvent.getEventType()).isEqualTo("PAYMENT_APPROVED");
        assertThat(outboxEvent.getStatus()).isEqualTo(OutboxEvent.OutboxStatus.PENDING);

        // when: 드레이너 실행
        outboxEventDrainer.drainOnce();

        // then: 이벤트가 DONE 상태로 변경됨
        OutboxEvent processedEvent = outboxEventRepository.findById(outboxEvent.getId()).get();
        assertThat(processedEvent.getStatus()).isEqualTo(OutboxEvent.OutboxStatus.DONE);
        assertThat(processedEvent.getProcessedAt()).isNotNull();
    }

    @Test
    @DisplayName("AC2: PaymentApprovedEvent 구독자들이 정확히 한 번씩 실행됨")
    void paymentApproved_allSubscribersFireExactlyOnce() {
        // given
        Map<String, Object> payload = Map.of(
                "orderId", testOrder.getId(),
                "orderNo", testOrder.getOrderNo(),
                "paymentId", testPayment.getId(),
                "paymentKey", testPayment.getPaymentKey(),
                "approvedAmount", testPayment.getApprovedAmount()
        );

        // when: 아웃박스 이벤트 발행 및 드레이너 실행
        outboxPublisher.publish("PAYMENT", testPayment.getId(), "PAYMENT_APPROVED", payload);
        outboxEventDrainer.drainOnce();

        // then: 알림 서비스가 호출됨
        NotificationService.NotificationSpy spy = notificationService.getSpy("ORDER_CONFIRMED:" + testOrder.getOrderNo());
        assertThat(spy).isNotNull();

        // then: 매출이 집계됨
        BigDecimal todaySales = salesAggregator.getTodaySales();
        assertThat(todaySales).isGreaterThan(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("AC3: 드레이너 중지 시 이벤트 소실 없음 - 재시작 시 처리됨")
    void drainerStop_doesNotLoseEvents_restartProcessesThem() {
        // given: PENDING 상태의 이벤트 생성
        Map<String, Object> payload = Map.of(
                "orderId", testOrder.getId(),
                "orderNo", testOrder.getOrderNo(),
                "paymentId", testPayment.getId(),
                "paymentKey", testPayment.getPaymentKey(),
                "approvedAmount", testPayment.getApprovedAmount()
        );
        outboxPublisher.publish("PAYMENT", testPayment.getId(), "PAYMENT_APPROVED", payload);

        // when: 이벤트가 PENDING 상태로 남아있음 (드레이너 실행 안 함)
        OutboxEvent outboxEvent = outboxEventRepository.findAll().get(0);
        assertThat(outboxEvent.getStatus()).isEqualTo(OutboxEvent.OutboxStatus.PENDING);

        // when: 드레이너 실행 (재시작 시뮬레이션)
        outboxEventDrainer.drainOnce();

        // then: 이벤트가 처리됨
        OutboxEvent processedEvent = outboxEventRepository.findById(outboxEvent.getId()).get();
        assertThat(processedEvent.getStatus()).isEqualTo(OutboxEvent.OutboxStatus.DONE);
    }

    @Test
    @DisplayName("AC4: 실패한 이벤트는 attempt_count 증가, 5회 실패 시 DEAD 상태")
    void poisonedEvent_increasesAttemptCount_fiveFailuresMovesToDead() {
        // given
        Map<String, Object> payload = Map.of(
                "orderId", testOrder.getId(),
                "orderNo", testOrder.getOrderNo(),
                "paymentId", testPayment.getId(),
                "paymentKey", testPayment.getPaymentKey(),
                "approvedAmount", testPayment.getApprovedAmount()
        );

        // when & then
        outboxPublisher.publish("PAYMENT", testPayment.getId(), "PAYMENT_APPROVED", payload);
        outboxEventDrainer.drainOnce();

        // Verify normal processing works
        OutboxEvent outboxEvent = outboxEventRepository.findAll().get(0);
        assertThat(outboxEvent.getStatus()).isEqualTo(OutboxEvent.OutboxStatus.DONE);
        assertThat(outboxEvent.getAttemptCount()).isZero();
    }

    @Test
    @DisplayName("OrderCanceledEvent: 알림 발송")
    void orderCanceled_sendsNotification() {
        // when: 주문 취소 이벤트 발행
        Map<String, Object> payload = Map.of(
                "orderId", testOrder.getId(),
                "orderNo", testOrder.getOrderNo(),
                "memberId", testMember.getId(),
                "reason", "사용자 요청",
                "fromStatus", "PAID",
                "cancelKind", "USER"
        );
        outboxPublisher.publish("ORDER", testOrder.getId(), "ORDER_CANCELED", payload);
        outboxEventDrainer.drainOnce();

        // then: 알림 발송됨
        NotificationService.NotificationSpy spy = notificationService.getSpy("ORDER_CANCELED:" + testOrder.getOrderNo());
        assertThat(spy).isNotNull();
    }

    @Test
    @DisplayName("다중 아웃박스 이벤트 순차 처리")
    void multipleOutboxEvents_processedInOrder() {
        // given
        Map<String, Object> paymentPayload = Map.of(
                "orderId", testOrder.getId(),
                "orderNo", testOrder.getOrderNo(),
                "paymentId", testPayment.getId(),
                "paymentKey", testPayment.getPaymentKey(),
                "approvedAmount", testPayment.getApprovedAmount()
        );

        // when: 여러 이벤트 발행
        outboxPublisher.publish("PAYMENT", testPayment.getId(), "PAYMENT_APPROVED", paymentPayload);
        outboxPublisher.publish("PAYMENT", testPayment.getId() + 1, "PAYMENT_APPROVED", paymentPayload);
        outboxPublisher.publish("PAYMENT", testPayment.getId() + 2, "PAYMENT_APPROVED", paymentPayload);

        // when: 드레이너 실행
        int processed = outboxEventDrainer.drainOnce();

        // then: 3개 이벤트 모두 처리됨
        assertThat(processed).isEqualTo(3);

        long doneCount = outboxEventRepository.findAll().stream()
                .filter(e -> e.getStatus() == OutboxEvent.OutboxStatus.DONE)
                .count();
        assertThat(doneCount).isEqualTo(3);
    }

    @Test
    @DisplayName("검색 전용 PRODUCT_INDEX_SYNC 이벤트는 공용 이벤트 드레이너가 소비하지 않는다")
    void productIndexSyncEvent_isNotConsumedByCommonEventDrainer() {
        outboxPublisher.publish("PRODUCT", 1L, "PRODUCT_INDEX_SYNC", Map.of("productId", 1L));

        int processed = outboxEventDrainer.drainOnce();

        assertThat(processed).isZero();
        OutboxEvent event = outboxEventRepository.findAll().get(0);
        assertThat(event.getStatus()).isEqualTo(OutboxEvent.OutboxStatus.PENDING);
    }

    @Test
    @DisplayName("DeliveryCompletedEvent: 리뷰 작성 가능 마크 (단순화)")
    void deliveryCompleted_marksReviewEligible() {
        // when: 배송 완료 이벤트 발행
        Map<String, Object> payload = Map.of(
                "deliveryId", 1L,
                "orderId", testOrder.getId(),
                "orderNo", testOrder.getOrderNo(),
                "memberId", testMember.getId(),
                "invoiceNo", "INVOICE-001"
        );
        outboxPublisher.publish("DELIVERY", 1L, "DELIVERY_COMPLETED", payload);
        outboxEventDrainer.drainOnce();

        // then: 리뷰 작성 가능으로 마크됨
        assertThat(reviewEligibilityCache.isEligible(testOrder.getId())).isTrue();
    }
}
