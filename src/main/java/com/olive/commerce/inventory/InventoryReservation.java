package com.olive.commerce.inventory;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * 주문별 재고 예약 Entity (PRD §7.4, §17.2).
 *
 * <p>주문 생성 시 {@code HELD} 상태로 생성되며, 결제 승인 시 {@code COMMITTED},
 * 실패/만료 시 {@code RELEASED}로 상태가 변경된다.
 */
@Entity
@Table(name = "inventory_reservations")
public class InventoryReservation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "product_option_id", nullable = false)
    private Long productOptionId;

    @Column(name = "quantity", nullable = false)
    private Integer quantity;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ReservationStatus status = ReservationStatus.HELD;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "finalized_at")
    private Instant finalizedAt;

    /**
     * 예약 상태 (V4__inventory.sql CHECK 제약과 일치).
     */
    public enum ReservationStatus {
        HELD,
        COMMITTED,
        RELEASED
    }

    protected InventoryReservation() {}

    private InventoryReservation(Long orderId, Long productOptionId, Integer quantity,
                                  Instant expiresAt) {
        this.orderId = orderId;
        this.productOptionId = productOptionId;
        this.quantity = quantity;
        this.status = ReservationStatus.HELD;
        this.createdAt = Instant.now();
        this.expiresAt = expiresAt;
        this.finalizedAt = null;
    }

    /**
     * 새 예약 생성 (HELD 상태).
     *
     * @param orderId   주문 ID
     * @param optionId  옵션 ID
     * @param quantity  예약 수량
     * @param ttl       TTL (예: 15분)
     */
    public static InventoryReservation createHeld(Long orderId, Long optionId,
                                                   Integer quantity, java.time.Duration ttl) {
        return new InventoryReservation(orderId, optionId, quantity, Instant.now().plus(ttl));
    }

    /**
     * 결제 승인 → COMMITTED 상태로 변경.
     */
    public void commit() {
        if (this.status != ReservationStatus.HELD) {
            throw new IllegalStateException(
                    "Cannot commit non-HELD reservation: " + this.status);
        }
        this.status = ReservationStatus.COMMITTED;
        this.finalizedAt = Instant.now();
    }

    /**
     * 결제 실패 또는 TTL 만료 → RELEASED 상태로 변경.
     *
     * @param alreadyCommitted 이미 COMMITTED 상태인지 (idempotent 체크용)
     * @return true if 상태가 실제로 변경됨, false if 이미 COMMITTED/RELEASED
     */
    public boolean release(boolean alreadyCommitted) {
        if (this.status == ReservationStatus.COMMITTED) {
            // 이미 확정된 예약은 해제하지 않음 (idempotent)
            return false;
        }
        if (this.status == ReservationStatus.RELEASED) {
            // 이미 해제됨 (idempotent)
            return false;
        }
        this.status = ReservationStatus.RELEASED;
        this.finalizedAt = Instant.now();
        return true;
    }

    /**
     * 만료 여부 확인 (배치 처리용).
     */
    public boolean isExpired() {
        return this.status == ReservationStatus.HELD
                && this.expiresAt.isBefore(Instant.now());
    }

    /**
     * 해당 주문+옵션 조합에 대한 예약이 이미 존재하는지 확인용.
     */
    public boolean matches(Long orderId, Long optionId) {
        return this.orderId.equals(orderId) && this.productOptionId.equals(optionId);
    }

    // Getters
    public Long getId() {
        return id;
    }

    public Long getOrderId() {
        return orderId;
    }

    public Long getProductOptionId() {
        return productOptionId;
    }

    public Integer getQuantity() {
        return quantity;
    }

    public ReservationStatus getStatus() {
        return status;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getFinalizedAt() {
        return finalizedAt;
    }
}
