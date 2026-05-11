package com.olive.commerce.inventory;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * 재고 본 테이블 Entity (PRD §6.7, §7.4).
 *
 * <p>한 row는 {@code product_option_id} 하나의 재고를 나타낸다.
 *
 * <p>{@code available_quantity}는 DB GENERATED 컬럼으로 서비스가 직접 수정하지 않는다.
 * Postgres가 {@code total_quantity - reserved_quantity}로 자동 계산한다 (V4__inventory.sql).
 */
@Entity
@Table(name = "inventories")
public class Inventory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "product_option_id", nullable = false, unique = true)
    private Long productOptionId;

    @Column(name = "total_quantity", nullable = false)
    private Integer totalQuantity = 0;

    @Column(name = "reserved_quantity", nullable = false)
    private Integer reservedQuantity = 0;

    /**
     * GENERATED ALWAYS AS (total_quantity - reserved_quantity) STORED
     * <p>서비스에서 직접 수정 금지. DB가 자동 계산한다.
     */
    @Column(name = "available_quantity", insertable = false, updatable = false)
    private Integer availableQuantity;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected Inventory() {}

    private Inventory(Long productOptionId) {
        this.productOptionId = productOptionId;
        this.totalQuantity = 0;
        this.reservedQuantity = 0;
        this.updatedAt = Instant.now();
    }

    /**
     * 새 재고 레코드 생성. 초기값은 total=0, reserved=0.
     */
    public static Inventory create(Long productOptionId) {
        return new Inventory(productOptionId);
    }

    /**
     * 재고 입고 (ADMIN_ADJUST 또는 STOCK_IN).
     *
     * @param delta 증가분 (양수)
     */
    public void addStock(int delta) {
        if (delta < 0) {
            throw new IllegalArgumentException("addStock delta must be positive: " + delta);
        }
        this.totalQuantity += delta;
        this.updatedAt = Instant.now();
    }

    /**
     * 재고 출고 (ADMIN_ADJUST 또는 STOCK_OUT).
     *
     * @param delta 감소분 (양수)
     */
    public void removeStock(int delta) {
        if (delta < 0) {
            throw new IllegalArgumentException("removeStock delta must be positive: " + delta);
        }
        if (this.totalQuantity - delta < 0) {
            throw new IllegalStateException(
                    "Cannot remove stock: total=" + this.totalQuantity + ", delta=" + delta);
        }
        this.totalQuantity -= delta;
        this.updatedAt = Instant.now();
    }

    /**
     * 예약 수량 증가 (reserve).
     *
     * @param delta 증가분 (양수)
     */
    public void reserve(int delta) {
        if (delta < 0) {
            throw new IllegalArgumentException("reserve delta must be positive: " + delta);
        }
        this.reservedQuantity += delta;
        this.updatedAt = Instant.now();
    }

    /**
     * 예약 수량 감소 + 전체 감소 (commit).
     *
     * @param delta 감소분 (양수)
     */
    public void commit(int delta) {
        if (delta < 0) {
            throw new IllegalArgumentException("commit delta must be positive: " + delta);
        }
        if (this.reservedQuantity < delta) {
            throw new IllegalStateException(
                    "Cannot commit: reserved=" + this.reservedQuantity + ", delta=" + delta);
        }
        if (this.totalQuantity < delta) {
            throw new IllegalStateException(
                    "Cannot commit: total=" + this.totalQuantity + ", delta=" + delta);
        }
        this.reservedQuantity -= delta;
        this.totalQuantity -= delta;
        this.updatedAt = Instant.now();
    }

    /**
     * 예약 수량만 감소 (release).
     *
     * @param delta 감소분 (양수)
     */
    public void release(int delta) {
        if (delta < 0) {
            throw new IllegalArgumentException("release delta must be positive: " + delta);
        }
        if (this.reservedQuantity < delta) {
            throw new IllegalStateException(
                    "Cannot release: reserved=" + this.reservedQuantity + ", delta=" + delta);
        }
        this.reservedQuantity -= delta;
        this.updatedAt = Instant.now();
    }

    /**
     * 현재 가용 재고가 요청 수량보다 크거나 같은지 검증.
     */
    public boolean hasAvailable(int requested) {
        return availableQuantity != null && availableQuantity >= requested;
    }

    // Getters
    public Long getId() {
        return id;
    }

    public Long getProductOptionId() {
        return productOptionId;
    }

    public Integer getTotalQuantity() {
        return totalQuantity;
    }

    public Integer getReservedQuantity() {
        return reservedQuantity;
    }

    public Integer getAvailableQuantity() {
        return availableQuantity;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
