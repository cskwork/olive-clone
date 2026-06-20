package com.olive.commerce.order;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.olive.commerce.common.audit.AuditLogger;
import com.olive.commerce.inventory.InventoryService;
import com.olive.commerce.payment.Payment;
import com.olive.commerce.payment.PaymentRepository;
import com.olive.commerce.payment.RefundRepository;
import com.olive.commerce.payment.client.PgClient;
import com.olive.commerce.payment.client.dto.CancelRequest;
import com.olive.commerce.payment.client.dto.CancelResponse;
import com.olive.commerce.promotion.CouponService;
import com.olive.commerce.promotion.PointService;
import com.olive.commerce.search.OutboxEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * OrderCancellationService 단위 테스트 — PG 취소 금액 정합성 (FIX-4).
 *
 * <p>PAID 주문을 취소할 때, 이미 승인된 부분 환불을 제외한 잔여 청구액만
 * PG에 취소 요청해야 한다. 그러지 않으면 부분 환불된 만큼 이중 환급이 발생한다.
 */
class OrderCancellationServiceTest {

    private OrderRepository orderRepository;
    private OrderStatusHistoryRepository orderStatusHistoryRepository;
    private OutboxEventRepository outboxEventRepository;
    private InventoryService inventoryService;
    private CouponService couponService;
    private PointService pointService;
    private PaymentRepository paymentRepository;
    private RefundRepository refundRepository;
    private PgClient pgClient;
    private AuditLogger auditLogger;
    private ApplicationEventPublisher eventPublisher;
    private ObjectMapper objectMapper;

    private OrderCancellationService service;

    private Order paidOrder;
    private Payment payment;
    private static final String PAYMENT_KEY = "mock-paid-key";

    @BeforeEach
    void setUp() {
        orderRepository = mock(OrderRepository.class);
        orderStatusHistoryRepository = mock(OrderStatusHistoryRepository.class);
        outboxEventRepository = mock(OutboxEventRepository.class);
        inventoryService = mock(InventoryService.class);
        couponService = mock(CouponService.class);
        pointService = mock(PointService.class);
        paymentRepository = mock(PaymentRepository.class);
        refundRepository = mock(RefundRepository.class);
        pgClient = mock(PgClient.class);
        auditLogger = mock(AuditLogger.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        objectMapper = new ObjectMapper();

        service = new OrderCancellationService(
                orderRepository, orderStatusHistoryRepository, outboxEventRepository,
                inventoryService, couponService, pointService,
                paymentRepository, refundRepository, pgClient,
                auditLogger, eventPublisher, objectMapper
        );

        // PAID 주문: 결제 승인액 47,000
        paidOrder = Order.create(1L, 100L);
        paidOrder.setId(100L);
        paidOrder.setOrderNo("ORD202606200001");
        paidOrder.setPriceDetails(
                BigDecimal.valueOf(50000),
                BigDecimal.valueOf(5000),
                BigDecimal.valueOf(1000),
                BigDecimal.valueOf(3000),
                BigDecimal.valueOf(47000)
        );
        // CREATED → PAYMENT_PENDING → PAID (상태 전이 규칙 준수)
        paidOrder.toPaymentPending();
        paidOrder.toPaid();
        paidOrder.setPaymentKey(PAYMENT_KEY);

        payment = Payment.createRequest(
                paidOrder, Payment.PaymentMethod.CARD, BigDecimal.valueOf(47000), UUID.randomUUID());
        payment.setId(200L);
        payment.approve(PAYMENT_KEY, "mock", BigDecimal.valueOf(47000), OffsetDateTime.now());

        lenient().when(orderRepository.findByOrderNo(paidOrder.getOrderNo()))
                .thenReturn(Optional.of(paidOrder));
        lenient().when(paymentRepository.findByOrderId(paidOrder.getId()))
                .thenReturn(Optional.of(payment));
        lenient().when(pgClient.cancelPayment(any(CancelRequest.class)))
                .thenReturn(new CancelResponse("CANCELED", LocalDateTime.now(), "취소"));
    }

    @Test
    void cancelPaymentAtPg_부분환불승인후_잔여금액만_PG취소() {
        // Given — 이미 22,000원 부분 환불 승인됨 → 잔여 25,000만 취소해야 함
        when(refundRepository.sumApprovedAmountByPaymentId(payment.getId()))
                .thenReturn(BigDecimal.valueOf(22000));

        // When — 사용자 주문 취소 (PAID → 취소)
        service.cancelUserOrder(paidOrder.getMemberId(), paidOrder.getOrderNo(), "단순 변심");

        // Then — PG에는 잔여 금액(25,000)만 전달
        ArgumentCaptor<CancelRequest> captor = ArgumentCaptor.forClass(CancelRequest.class);
        verify(pgClient).cancelPayment(captor.capture());
        assertThat(captor.getValue().amount()).isEqualByComparingTo(BigDecimal.valueOf(25000));
        assertThat(captor.getValue().paymentKey()).isEqualTo(PAYMENT_KEY);

        assertThat(payment.getStatus()).isEqualTo(Payment.PaymentStatus.CANCELED);
        assertThat(paidOrder.getStatus()).isEqualTo(Order.OrderStatus.CANCELED);
    }

    @Test
    void cancelPaymentAtPg_환불없으면_전액_PG취소() {
        // Given — 승인된 환불 없음 → 결제 승인액 전체(47,000) 취소
        when(refundRepository.sumApprovedAmountByPaymentId(payment.getId()))
                .thenReturn(BigDecimal.ZERO);

        // When
        service.cancelUserOrder(paidOrder.getMemberId(), paidOrder.getOrderNo(), "단순 변심");

        // Then
        ArgumentCaptor<CancelRequest> captor = ArgumentCaptor.forClass(CancelRequest.class);
        verify(pgClient).cancelPayment(captor.capture());
        assertThat(captor.getValue().amount()).isEqualByComparingTo(BigDecimal.valueOf(47000));
    }

    @Test
    void cancelPaymentAtPg_전액환불완료시_PG재호출없이_CANCELED() {
        // Given — 이미 전액(47,000) 환불 승인됨 → 잔여 0 → PG 호출 생략
        when(refundRepository.sumApprovedAmountByPaymentId(payment.getId()))
                .thenReturn(BigDecimal.valueOf(47000));

        // When
        service.cancelUserOrder(paidOrder.getMemberId(), paidOrder.getOrderNo(), "단순 변심");

        // Then — PG cancel은 호출되지 않고 payment만 CANCELED 정리
        verify(pgClient, never()).cancelPayment(any(CancelRequest.class));
        assertThat(payment.getStatus()).isEqualTo(Payment.PaymentStatus.CANCELED);
        assertThat(paidOrder.getStatus()).isEqualTo(Order.OrderStatus.CANCELED);
    }
}
