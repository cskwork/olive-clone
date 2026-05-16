package com.olive.commerce.delivery;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;

/**
 * 배송 상태 변경 이력 엔티티 (PRD §16.2 audit log).
 * <p>
 * 모든 상태 변경을 기록하여 감사 추적성을 보장합니다.
 */
@Entity
@Table(name = "delivery_status_histories")
public class DeliveryStatusHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "delivery_id", nullable = false)
    private Delivery delivery;

    @Column(name = "from_status", length = 30)
    private String fromStatus;

    @Column(name = "to_status", nullable = false, length = 30)
    private String toStatus;

    @Column(name = "reason", length = 255)
    private String reason;

    @Column(name = "created_at", insertable = false, updatable = false)
    @CreationTimestamp
    private OffsetDateTime createdAt;

    protected DeliveryStatusHistory() {}

    private DeliveryStatusHistory(
            Delivery delivery,
            String fromStatus,
            String toStatus,
            String reason
    ) {
        this.delivery = delivery;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.reason = reason;
    }

    public static DeliveryStatusHistory initial(Delivery delivery) {
        return new DeliveryStatusHistory(
                delivery,
                null,
                Delivery.DeliveryStatus.READY.name(),
                "배송 생성"
        );
    }

    public static DeliveryStatusHistory transition(
            Delivery delivery,
            String fromStatus,
            String toStatus,
            String reason
    ) {
        return new DeliveryStatusHistory(delivery, fromStatus, toStatus, reason);
    }

    // Getters
    public Long getId() { return id; }
    public Delivery getDelivery() { return delivery; }
    public String getFromStatus() { return fromStatus; }
    public String getToStatus() { return toStatus; }
    public String getReason() { return reason; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
