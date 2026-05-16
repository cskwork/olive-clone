package com.olive.commerce.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.olive.commerce.common.audit.AuditLogger;
import com.olive.commerce.common.error.ErrorCode;
import com.olive.commerce.inventory.InventoryService;
import com.olive.commerce.order.Order;
import com.olive.commerce.order.OrderItem;
import com.olive.commerce.order.OrderRepository;
import com.olive.commerce.order.OrderStatusHistoryRepository;
import com.olive.commerce.payment.Payment.PaymentStatus;
import com.olive.commerce.payment.Refund.RefundStatus;
import com.olive.commerce.payment.client.PgClient;
import com.olive.commerce.payment.client.dto.RefundRequest;
import com.olive.commerce.payment.client.dto.RefundResponse;
import com.olive.commerce.promotion.PointService;
import com.olive.commerce.search.OutboxEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * RefundService 단위 테스트 (OLV-074).
 */
class RefundServiceTest {

    private RefundRepository refundRepository;
    private PaymentRepository paymentRepository;
    private OrderRepository orderRepository;
    private OrderStatusHistoryRepository orderStatusHistoryRepository;
    private OutboxEventRepository outboxEventRepository;
    private PgClient pgClient;
    private InventoryService inventoryService;
    private PointService pointService;
    private AuditLogger auditLogger;
    private ApplicationEventPublisher eventPublisher;
    private ObjectMapper objectMapper;

    private RefundService refundService;
    private Order testOrder;
    private Payment testPayment;

    @BeforeEach
    void setUp() {
        refundRepository = mock(RefundRepository.class);
        paymentRepository = mock(PaymentRepository.class);
        orderRepository = mock(OrderRepository.class);
        orderStatusHistoryRepository = mock(OrderStatusHistoryRepository.class);
        outboxEventRepository = mock(OutboxEventRepository.class);
        pgClient = mock(PgClient.class);
        inventoryService = mock(InventoryService.class);
        pointService = mock(PointService.class);
        auditLogger = mock(AuditLogger.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        objectMapper = new ObjectMapper();

        refundService = new RefundService(
                refundRepository, paymentRepository, orderRepository, orderStatusHistoryRepository,
                outboxEventRepository, pgClient, inventoryService, pointService,
                auditLogger, eventPublisher, objectMapper
        );

        // 테스트용 주문 생성
        testOrder = Order.create(1L, 100L);
        testOrder.setId(100L);
        testOrder.setOrderNo("ORD202605120001");
        testOrder.setPriceDetails(
                BigDecimal.valueOf(50000),
                BigDecimal.valueOf(5000),
                BigDecimal.valueOf(1000),
                BigDecimal.valueOf(3000),
                BigDecimal.valueOf(47000)
        );
        testOrder.setStatusDirectly(Order.OrderStatus.DELIVERED);

        OrderItem item = OrderItem.create(
                testOrder, 1L, 100L, "Product", "Option",
                BigDecimal.valueOf(25000), 2
        );
        testOrder.addItem(item);

        // 테스트용 결제 생성
        testPayment = Payment.createRequest(
                testOrder,
                Payment.PaymentMethod.CARD,
                BigDecimal.valueOf(47000),
                UUID.randomUUID()
        );
        testPayment.setId(200L);
        testPayment.approve("mock-payment-key", "mock", BigDecimal.valueOf(47000), OffsetDateTime.now());
    }

    @Test
    void requestRefund_DELIVERED주문_환불요청_성공() {
        // Given
        String orderNo = testOrder.getOrderNo();
        Long memberId = testOrder.getMemberId();
        RefundDtos.RefundRequestDto request = new RefundDtos.RefundRequestDto("단순 변심", List.of());

        when(orderRepository.findByOrderNo(orderNo)).thenReturn(Optional.of(testOrder));
        when(paymentRepository.findByOrderId(testOrder.getId())).thenReturn(Optional.of(testPayment));
        when(refundRepository.findByOrderIdAndStatus(testOrder.getId(), RefundStatus.REQUESTED))
                .thenReturn(Optional.empty());
        when(refundRepository.sumApprovedAmountByPaymentId(testPayment.getId())).thenReturn(BigDecimal.ZERO);
        when(refundRepository.save(any(Refund.class))).thenAnswer(inv -> {
            Refund r = inv.getArgument(0);
            r.setId(1L);
            return r;
        });
        when(orderRepository.save(any(Order.class))).thenReturn(testOrder);
        when(orderStatusHistoryRepository.save(any())).thenReturn(mock(com.olive.commerce.order.OrderStatusHistory.class));

        // When
        Refund result = refundService.requestRefund(memberId, orderNo, request);

        // Then
        assertThat(result.getStatus()).isEqualTo(RefundStatus.REQUESTED);
        assertThat(result.getAmount()).isEqualTo(testOrder.getFinalPaymentAmount());
        assertThat(result.getReason()).isEqualTo("단순 변심");
        assertThat(result.getOrderId()).isEqualTo(testOrder.getId());

        verify(auditLogger).log(eq("REFUND_REQUESTED"), any());
    }

    @Test
    void requestRefund_타인주문_거절() {
        // Given
        String orderNo = testOrder.getOrderNo();
        Long otherMemberId = 999L;
        RefundDtos.RefundRequestDto request = new RefundDtos.RefundRequestDto("단순 변심", List.of());

        when(orderRepository.findByOrderNo(orderNo)).thenReturn(Optional.of(testOrder));

        // When & Then
        assertThatThrownBy(() -> refundService.requestRefund(otherMemberId, orderNo, request))
                .hasMessageContaining("Not your order");
    }

    @Test
    void requestRefund_DELIVERED아니면_거절() {
        // Given
        testOrder.setStatusDirectly(Order.OrderStatus.PREPARING);
        String orderNo = testOrder.getOrderNo();
        Long memberId = testOrder.getMemberId();
        RefundDtos.RefundRequestDto request = new RefundDtos.RefundRequestDto("단순 변심", List.of());

        when(orderRepository.findByOrderNo(orderNo)).thenReturn(Optional.of(testOrder));

        // When & Then
        assertThatThrownBy(() -> refundService.requestRefund(memberId, orderNo, request))
                .hasMessageContaining("DELIVERED");
    }

    @Test
    void approveRefund_PG호출_재고복구_포인트복구() {
        // Given
        Refund refund = Refund.request(testPayment, testOrder, testOrder.getFinalPaymentAmount(), "단순 변심");
        refund.setId(1L);
        Long adminId = 100L;

        when(refundRepository.findById(refund.getId())).thenReturn(Optional.of(refund));
        when(pgClient.refund(any(RefundRequest.class)))
                .thenReturn(new RefundResponse("REFUNDED", LocalDateTime.now(), "mock-refund-key"));
        when(refundRepository.save(any(Refund.class))).thenReturn(refund);
        when(paymentRepository.save(any(Payment.class))).thenReturn(testPayment);
        when(orderRepository.save(any(Order.class))).thenReturn(testOrder);
        when(orderStatusHistoryRepository.save(any())).thenReturn(mock(com.olive.commerce.order.OrderStatusHistory.class));

        // When
        RefundDtos.ApproveResponse result = refundService.approveRefund(refund.getId(), adminId);

        // Then
        assertThat(result.status()).isEqualTo(RefundStatus.APPROVED);
        assertThat(result.pgRefundKey()).isEqualTo("mock-refund-key");
        assertThat(refund.getStatus()).isEqualTo(RefundStatus.APPROVED);
        assertThat(testPayment.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(testOrder.getStatus()).isEqualTo(Order.OrderStatus.REFUNDED);

        // Verify PG called once
        verify(pgClient, times(1)).refund(any(RefundRequest.class));

        // Verify inventory restocked for each item
        verify(inventoryService, times(testOrder.getItems().size()))
                .adjust(anyLong(), anyInt(), anyString(), eq(adminId));

        // Verify points restored
        if (testOrder.getPointUsedAmount().compareTo(BigDecimal.ZERO) > 0) {
            verify(pointService).cancel(testOrder.getMemberId(), testOrder.getId());
        }

        verify(auditLogger).log(eq("REFUND_APPROVED"), any());
        verify(eventPublisher).publishEvent(any(OrderRefundedEvent.class));
    }

    @Test
    void approveRefund_이미승인된_면등성() {
        // Given
        Refund refund = Refund.request(testPayment, testOrder, testOrder.getFinalPaymentAmount(), "단순 변심");
        refund.approve("existing-key", OffsetDateTime.now());
        refund.setId(1L);
        Long adminId = 100L;

        when(refundRepository.findById(refund.getId())).thenReturn(Optional.of(refund));

        // When
        RefundDtos.ApproveResponse result = refundService.approveRefund(refund.getId(), adminId);

        // Then
        assertThat(result.status()).isEqualTo(RefundStatus.APPROVED);
        assertThat(result.message()).contains("Already approved");

        // PG should NOT be called again
        verify(pgClient, never()).refund(any(RefundRequest.class));
        verify(inventoryService, never()).adjust(anyLong(), anyInt(), anyString(), anyLong());
    }

    @Test
    void rejectRefund_거절성공_주문상태복구() {
        // Given
        Refund refund = Refund.request(testPayment, testOrder, testOrder.getFinalPaymentAmount(), "단순 변심");
        refund.setId(1L);
        testOrder.setStatusDirectly(Order.OrderStatus.REFUND_REQUESTED);

        RefundDtos.RejectRequest request = new RefundDtos.RejectRequest("사유 불충분");
        Long adminId = 100L;

        when(refundRepository.findById(refund.getId())).thenReturn(Optional.of(refund));
        when(refundRepository.save(any(Refund.class))).thenReturn(refund);
        when(orderRepository.save(any(Order.class))).thenReturn(testOrder);
        when(orderStatusHistoryRepository.save(any())).thenReturn(mock(com.olive.commerce.order.OrderStatusHistory.class));

        // When
        Refund result = refundService.rejectRefund(refund.getId(), request, adminId);

        // Then
        assertThat(result.getStatus()).isEqualTo(RefundStatus.FAILED);
        assertThat(result.getFailedReason()).isEqualTo("사유 불충분");
        assertThat(testOrder.getStatus()).isEqualTo(Order.OrderStatus.DELIVERED);

        verify(auditLogger).log(eq("REFUND_REJECTED"), any());
    }

    @Test
    void rejectRefund_이미거절된_면등성() {
        // Given
        Refund refund = Refund.request(testPayment, testOrder, testOrder.getFinalPaymentAmount(), "단순 변심");
        refund.reject("이미 거절됨");
        refund.setId(1L);

        RefundDtos.RejectRequest request = new RefundDtos.RejectRequest("다시 거절");
        Long adminId = 100L;

        when(refundRepository.findById(refund.getId())).thenReturn(Optional.of(refund));

        // When
        Refund result = refundService.rejectRefund(refund.getId(), request, adminId);

        // Then
        assertThat(result.getStatus()).isEqualTo(RefundStatus.FAILED);
        // Repository save should NOT be called (idempotent)
        verify(refundRepository, never()).save(any());
    }

    @Test
    void listRefunds_STATUS필터링() {
        // Given
        Pageable pageable = PageRequest.of(0, 20);
        Refund refund = Refund.request(testPayment, testOrder, testOrder.getFinalPaymentAmount(), "단순 변심");
        refund.setId(1L);

        when(refundRepository.findByStatus(RefundStatus.REQUESTED))
                .thenReturn(List.of(refund));

        // When
        Page<RefundDtos.AdminResponse> result = refundService.listRefunds(RefundStatus.REQUESTED, pageable);

        // Then
        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).refundId()).isEqualTo(1L);
        assertThat(result.getContent().get(0).status()).isEqualTo(RefundStatus.REQUESTED);
    }
}
