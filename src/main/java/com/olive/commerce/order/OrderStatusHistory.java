package com.olive.commerce.order;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;

/**
 * 주문 상태 변경 이력 엔티티 (PRD §16.2 audit log).
 * <p>
 * 모든 상태 변경을 기록하여 감사 추적성을 보장합니다.
 */
@Entity
@Table(name = "order_status_histories")
public class OrderStatusHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @Column(name = "from_status", length = 30)
    private String fromStatus;

    @Column(name = "to_status", nullable = false, length = 30)
    private String toStatus;

    @Column(name = "reason", length = 255)
    private String reason;

    @Column(name = "changed_by_kind", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private ChangedByKind changedByKind;

    @Column(name = "changed_by_id")
    private Long changedById;

    @Column(name = "created_at", insertable = false, updatable = false)
    @CreationTimestamp
    private OffsetDateTime createdAt;

    protected OrderStatusHistory() {}

    private OrderStatusHistory(
            Order order,
            String fromStatus,
            String toStatus,
            ChangedByKind changedByKind,
            Long changedById,
            String reason
    ) {
        this.order = order;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.changedByKind = changedByKind;
        this.changedById = changedById;
        this.reason = reason;
    }

    public static OrderStatusHistory initial(Order order) {
        return new OrderStatusHistory(
                order,
                null,
                Order.OrderStatus.CREATED.name(),
                ChangedByKind.SYSTEM,
                null,
                "주문 생성"
        );
    }

    public static OrderStatusHistory transition(
            Order order,
            String fromStatus,
            String toStatus,
            ChangedByKind changedByKind,
            Long changedById,
            String reason
    ) {
        return new OrderStatusHistory(order, fromStatus, toStatus, changedByKind, changedById, reason);
    }

    // Getters
    public Long getId() { return id; }
    public Order getOrder() { return order; }
    public String getFromStatus() { return fromStatus; }
    public String getToStatus() { return toStatus; }
    public String getReason() { return reason; }
    public ChangedByKind getChangedByKind() { return changedByKind; }
    public Long getChangedById() { return changedById; }
    public OffsetDateTime getCreatedAt() { return createdAt; }

    /**
     * 상태 변경 주체 (PRD §16.2).
     */
    public enum ChangedByKind {
        USER,   // 회원
        ADMIN,  // 관리자
        SYSTEM  // 시스템 자동
    }
}
