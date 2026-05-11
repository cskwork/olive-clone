package com.olive.commerce.inventory;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * 재고 변경 이력 Entity (append-only, PRD §7.4).
 *
 * <p>모든 재고 변동(입고/출고/예약/확정/해제/관리자 조정)은
 * 이 테이블에 기록되며, 한 번도 수정/삭제되지 않는다 (audit trail).
 */
@Entity
@Table(name = "inventory_histories")
public class InventoryHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "product_option_id", nullable = false)
    private Long productOptionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "change_type", nullable = false)
    private ChangeType changeType;

    @Column(name = "quantity_delta", nullable = false)
    private Integer quantityDelta;

    @Column(name = "reason", length = 255)
    private String reason;

    @Column(name = "order_id")
    private Long orderId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "created_by")
    private Long createdBy;

    /**
     * 변경 타입 (V4__inventory.sql CHECK 제약과 일치).
     */
    public enum ChangeType {
        STOCK_IN,
        STOCK_OUT,
        RESERVE,
        COMMIT,
        RELEASE,
        ADMIN_ADJUST
    }

    protected InventoryHistory() {}

    private InventoryHistory(Long productOptionId, ChangeType changeType, Integer quantityDelta,
                             String reason, Long orderId, Long createdBy) {
        this.productOptionId = productOptionId;
        this.changeType = changeType;
        this.quantityDelta = quantityDelta;
        this.reason = reason;
        this.orderId = orderId;
        this.orderId = orderId;
        this.createdAt = Instant.now();
        this.createdBy = createdBy;
    }

    /**
     * 시스템 자동 생성 이력 (예: reserve/commit/release).
     */
    public static InventoryHistory system(Long productOptionId, ChangeType changeType,
                                          Integer quantityDelta, String reason, Long orderId) {
        return new InventoryHistory(productOptionId, changeType, quantityDelta, reason, orderId, null);
    }

    /**
     * 관리자 수동 조정 이력 (ADMIN_ADJUST).
     */
    public static InventoryHistory manual(Long productOptionId, Integer quantityDelta,
                                          String reason, Long adminId) {
        return new InventoryHistory(productOptionId, ChangeType.ADMIN_ADJUST, quantityDelta, reason, null, adminId);
    }

    // Getters
    public Long getId() {
        return id;
    }

    public Long getProductOptionId() {
        return productOptionId;
    }

    public ChangeType getChangeType() {
        return changeType;
    }

    public Integer getQuantityDelta() {
        return quantityDelta;
    }

    public String getReason() {
        return reason;
    }

    public Long getOrderId() {
        return orderId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Long getCreatedBy() {
        return createdBy;
    }
}
