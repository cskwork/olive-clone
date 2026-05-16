package com.olive.commerce.delivery;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 배송 엔티티 (PRD §6.9).
 * <p>
 * 상태 전이 머신:
 * <pre>
 * READY → INVOICE → SHIPPING → DELIVERED
 *                      ↓
 *                 RETURNING → RETURNED
 * </pre>
 */
@Entity
@Table(name = "deliveries")
public class Delivery {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "delivery_address_id", nullable = false)
    private Long deliveryAddressId;

    @Column(name = "carrier_name", nullable = false, length = 50)
    private String carrierName = "MOCK";

    @Column(name = "invoice_no", length = 255)
    private String invoiceNo;

    @Column(name = "status", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private DeliveryStatus status = DeliveryStatus.READY;

    @Column(name = "created_at", updatable = false)
    @CreationTimestamp
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    @UpdateTimestamp
    private OffsetDateTime updatedAt;

    @OneToMany(mappedBy = "delivery")
    private List<DeliveryStatusHistory> statusHistories = new ArrayList<>();

    protected Delivery() {}

    private Delivery(Long orderId, Long deliveryAddressId) {
        this.orderId = orderId;
        this.deliveryAddressId = deliveryAddressId;
    }

    /**
     * 주문에 대한 배송을 생성합니다.
     */
    public static Delivery create(Long orderId, Long deliveryAddressId) {
        return new Delivery(orderId, deliveryAddressId);
    }

    /**
     * 운송장 번호를 설정하고 INVOICE 상태로 전이합니다.
     */
    public void assignInvoice(String invoiceNo) {
        validateTransition(DeliveryStatus.INVOICE);
        this.invoiceNo = invoiceNo;
        this.status = DeliveryStatus.INVOICE;
    }

    /**
     * 배송중 상태로 전이합니다.
     */
    public void toShipping() {
        validateTransition(DeliveryStatus.SHIPPING);
        this.status = DeliveryStatus.SHIPPING;
    }

    /**
     * 배송 완료 상태로 전이합니다.
     */
    public void toDelivered() {
        validateTransition(DeliveryStatus.DELIVERED);
        this.status = DeliveryStatus.DELIVERED;
    }

    /**
     * 반품중 상태로 전이합니다.
     */
    public void toReturning() {
        validateTransition(DeliveryStatus.RETURNING);
        this.status = DeliveryStatus.RETURNING;
    }

    /**
     * 반품 완료 상태로 전이합니다.
     */
    public void toReturned() {
        validateTransition(DeliveryStatus.RETURNED);
        this.status = DeliveryStatus.RETURNED;
    }

    private void validateTransition(DeliveryStatus to) {
        if (!isValidTransition(to)) {
            throw new IllegalStateException(
                String.format("Invalid status transition: %s → %s", this.status, to)
            );
        }
    }

    private boolean isValidTransition(DeliveryStatus to) {
        return switch (this.status) {
            case READY -> to == DeliveryStatus.INVOICE;
            case INVOICE -> to == DeliveryStatus.SHIPPING;
            case SHIPPING -> to == DeliveryStatus.DELIVERED || to == DeliveryStatus.RETURNING;
            case DELIVERED -> false; // Terminal
            case RETURNING -> to == DeliveryStatus.RETURNED;
            case RETURNED -> false; // Terminal
        };
    }

    // Getters
    public Long getId() { return id; }

    public void setId(Long id) { this.id = id; }

    public Long getOrderId() { return orderId; }

    public Long getDeliveryAddressId() { return deliveryAddressId; }

    public String getCarrierName() { return carrierName; }

    public void setCarrierName(String carrierName) { this.carrierName = carrierName; }

    public String getInvoiceNo() { return invoiceNo; }

    public DeliveryStatus getStatus() { return status; }

    public OffsetDateTime getCreatedAt() { return createdAt; }

    public OffsetDateTime getUpdatedAt() { return updatedAt; }

    public List<DeliveryStatusHistory> getStatusHistories() { return statusHistories; }

    /**
     * 배송 상태 (PRD §6.9).
     */
    public enum DeliveryStatus {
        READY,       // 배송 준비
        INVOICE,     // 운송장 등록
        SHIPPING,    // 배송중
        DELIVERED,   // 배송 완료
        RETURNING,   // 반품중
        RETURNED     // 반품 완료
    }
}
