package com.olive.commerce.order;

import com.olive.commerce.member.MemberAddress;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 주문 엔티티 (PRD §6.5, §7.5).
 * <p>
 * 상태 전이 머신:
 * <pre>
 * CREATED → PAYMENT_PENDING → PAID → PREPARING → SHIPPING → DELIVERED
 *                                  ↓
 *                             CANCELED / REFUND_REQUESTED / REFUNDED / FAILED
 * </pre>
 */
@Entity
@Table(name = "orders")
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_no", unique = true, nullable = false, length = 50)
    private String orderNo;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Column(name = "status", nullable = false, length = 30)
    private String status = OrderStatus.CREATED.name();

    @Column(name = "total_product_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal totalProductAmount = BigDecimal.ZERO;

    @Column(name = "discount_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal discountAmount = BigDecimal.ZERO;

    @Column(name = "point_used_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal pointUsedAmount = BigDecimal.ZERO;

    @Column(name = "delivery_fee", nullable = false, precision = 12, scale = 2)
    private BigDecimal deliveryFee = BigDecimal.ZERO;

    @Column(name = "final_payment_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal finalPaymentAmount = BigDecimal.ZERO;

    @Column(name = "used_member_coupon_id")
    private Long usedMemberCouponId;

    @OneToOne
    @JoinColumn(name = "delivery_address_id", nullable = false, insertable = false, updatable = false)
    private MemberAddress deliveryAddress;

    @Column(name = "delivery_address_id", nullable = false)
    private Long deliveryAddressId;

    @Column(name = "created_at", updatable = false)
    @CreationTimestamp
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    @UpdateTimestamp
    private OffsetDateTime updatedAt;

    @OneToMany(mappedBy = "order")
    private List<OrderItem> items = new ArrayList<>();

    protected Order() {}

    private Order(Long memberId, Long deliveryAddressId) {
        this.memberId = memberId;
        this.deliveryAddressId = deliveryAddressId;
    }

    public static Order create(Long memberId, Long deliveryAddressId) {
        return new Order(memberId, deliveryAddressId);
    }

    public void setOrderNo(String orderNo) {
        this.orderNo = orderNo;
    }

    public void setPriceDetails(
            BigDecimal totalProductAmount,
            BigDecimal discountAmount,
            BigDecimal pointUsedAmount,
            BigDecimal deliveryFee,
            BigDecimal finalPaymentAmount
    ) {
        this.totalProductAmount = totalProductAmount;
        this.discountAmount = discountAmount;
        this.pointUsedAmount = pointUsedAmount;
        this.deliveryFee = deliveryFee;
        this.finalPaymentAmount = finalPaymentAmount;
    }

    public void setUsedMemberCouponId(Long usedMemberCouponId) {
        this.usedMemberCouponId = usedMemberCouponId;
    }

    public void addItem(OrderItem item) {
        this.items.add(item);
    }

    /**
     * 결제 대기 상태로 전이 (OLV-061 Step 8 이후).
     */
    public void toPaymentPending() {
        validateTransition(OrderStatus.PAYMENT_PENDING);
        this.status = OrderStatus.PAYMENT_PENDING.name();
    }

    /**
     * 결제 완료 상태로 전이 (PG 웹훅 수신 후).
     */
    public void toPaid() {
        validateTransition(OrderStatus.PAID);
        this.status = OrderStatus.PAID.name();
    }

    /**
     * 상품 준비중 상태로 전이.
     */
    public void toPreparing() {
        validateTransition(OrderStatus.PREPARING);
        this.status = OrderStatus.PREPARING.name();
    }

    /**
     * 배송중 상태로 전이.
     */
    public void toShipping() {
        validateTransition(OrderStatus.SHIPPING);
        this.status = OrderStatus.SHIPPING.name();
    }

    /**
     * 배송 완료 상태로 전이.
     */
    public void toDelivered() {
        validateTransition(OrderStatus.DELIVERED);
        this.status = OrderStatus.DELIVERED.name();
    }

    /**
     * 취소 상태로 전이.
     */
    public void toCanceled(String reason) {
        validateTransition(OrderStatus.CANCELED);
        this.status = OrderStatus.CANCELED.name();
    }

    /**
     * 관리자 강제 취소 상태로 전이.
     * <p>
     * 상태 전이 규칙을 우회하고 모든 비종단 상태에서 CANCELED로 전이합니다.
     */
    public void forceCanceled(String reason) {
        Order.OrderStatus current = getStatus();
        if (current == Order.OrderStatus.CANCELED ||
            current == Order.OrderStatus.REFUNDED ||
            current == Order.OrderStatus.FAILED) {
            throw new IllegalStateException(
                String.format("Cannot force cancel terminal state: %s", current)
            );
        }
        this.status = OrderStatus.CANCELED.name();
    }

    /**
     * 실패 상태로 전이 (결제 타임아웃 등).
     */
    public void toFailed() {
        validateTransition(OrderStatus.FAILED);
        this.status = OrderStatus.FAILED.name();
    }

    /**
     * 관리자에 의한 직접 상태 변경 (OLV-063).
     * <p>
     * 상태 전이 검증은 서비스 레이어에서 수행합니다.
     *
     * @param status 설정할 상태
     */
    public void setStatusDirectly(OrderStatus status) {
        this.status = status.name();
    }

    /**
     * 내부용 상태 설정자 (배송지 정보 로드 등).
     */
    public void setStatus(String status) {
        this.status = status;
    }

    private void validateTransition(OrderStatus to) {
        OrderStatus from = OrderStatus.valueOf(this.status);
        if (!isValidTransition(from, to)) {
            throw new IllegalStateException(
                String.format("Invalid status transition: %s → %s", from, to)
            );
        }
    }

    private boolean isValidTransition(OrderStatus from, OrderStatus to) {
        return switch (from) {
            case CREATED -> to == OrderStatus.PAYMENT_PENDING || to == OrderStatus.FAILED;
            case PAYMENT_PENDING -> to == OrderStatus.PAID || to == OrderStatus.CANCELED || to == OrderStatus.FAILED;
            case PAID -> to == OrderStatus.PREPARING || to == OrderStatus.CANCELED;
            case PREPARING -> to == OrderStatus.SHIPPING || to == OrderStatus.CANCELED;
            case SHIPPING -> to == OrderStatus.DELIVERED || to == OrderStatus.REFUND_REQUESTED;
            case DELIVERED -> to == OrderStatus.REFUND_REQUESTED;
            case REFUND_REQUESTED -> to == OrderStatus.REFUNDED;
            case CANCELED, REFUNDED, FAILED -> false; // Terminal states
        };
    }

    // Getters
    public Long getId() { return id; }
    public String getOrderNo() { return orderNo; }
    public Long getMemberId() { return memberId; }
    public OrderStatus getStatus() { return OrderStatus.valueOf(status); }
    public BigDecimal getTotalProductAmount() { return totalProductAmount; }
    public BigDecimal getDiscountAmount() { return discountAmount; }
    public BigDecimal getPointUsedAmount() { return pointUsedAmount; }
    public BigDecimal getDeliveryFee() { return deliveryFee; }
    public BigDecimal getFinalPaymentAmount() { return finalPaymentAmount; }
    public Long getUsedMemberCouponId() { return usedMemberCouponId; }
    public Long getDeliveryAddressId() { return deliveryAddressId; }
    public MemberAddress getDeliveryAddress() { return deliveryAddress; }
    public List<OrderItem> getItems() { return items; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }

    /**
     * 주문 상태 (PRD §6.5).
     */
    public enum OrderStatus {
        CREATED,           // 주문 생성
        PAYMENT_PENDING,   // 결제 대기
        PAID,              // 결제 완료
        PREPARING,         // 상품 준비중
        SHIPPING,          // 배송중
        DELIVERED,         // 배송 완료
        CANCELED,          // 주문 취소
        REFUND_REQUESTED,  // 환불 요청
        REFUNDED,          // 환불 완료
        FAILED             // 주문 실패
    }
}
