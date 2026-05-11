-- OLV-060 Order domain schema (PRD §6.5, §7.5-7.6, §20.2).
-- 본 파일은 applied 후 절대 수정 금지 (Hard rule). 후속 변경은 V8+ 로 추가.

-- ---------------------------------------------------------------------------
-- 0. order_no 생성기 위한 시퀀스
-- ---------------------------------------------------------------------------
-- format: ORD<yyyyMMdd><6-digit-seq>, 매일 자정에 리셋됨
CREATE SEQUENCE order_no_seq_yyyymmdd
    START WITH 1
    INCREMENT BY 1
    MINVALUE 1
    MAXVALUE 999999
    CYCLE;

-- ---------------------------------------------------------------------------
-- 1. orders — 주문 본 테이블 (PRD §7.5).
-- ---------------------------------------------------------------------------
CREATE TABLE orders (
    id                       BIGSERIAL PRIMARY KEY,
    order_no                 VARCHAR(50)  NOT NULL UNIQUE,
    member_id                BIGINT       NOT NULL REFERENCES members(id),
    status                   VARCHAR(30)  NOT NULL DEFAULT 'CREATED',
    total_product_amount     DECIMAL(12, 2) NOT NULL DEFAULT 0.00,
    discount_amount          DECIMAL(12, 2) NOT NULL DEFAULT 0.00,
    point_used_amount        DECIMAL(12, 2) NOT NULL DEFAULT 0.00,
    delivery_fee             DECIMAL(12, 2) NOT NULL DEFAULT 0.00,
    final_payment_amount     DECIMAL(12, 2) NOT NULL DEFAULT 0.00,
    used_member_coupon_id    BIGINT,
    delivery_address_id      BIGINT       NOT NULL REFERENCES member_addresses(id),
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT orders_status_check
        CHECK (status IN (
            'CREATED', 'PAYMENT_PENDING', 'PAID', 'PREPARING',
            'SHIPPING', 'DELIVERED', 'CANCELED', 'REFUND_REQUESTED',
            'REFUNDED', 'FAILED'
        )),
    CONSTRAINT orders_amount_non_negative
        CHECK (
            total_product_amount >= 0 AND
            discount_amount >= 0 AND
            point_used_amount >= 0 AND
            delivery_fee >= 0 AND
            final_payment_amount >= 0
        )
);

COMMENT ON TABLE orders IS 'Order header (PRD §7.5). Integration hub for member/product/payment.';
COMMENT ON COLUMN orders.order_no IS 'Human-readable order number: ORD<yyyyMMdd><6-digit-seq>.';
COMMENT ON COLUMN orders.status IS 'Order status flow: CREATED → PAYMENT_PENDING → PAID → PREPARING → SHIPPING → DELIVERED.';
COMMENT ON COLUMN orders.used_member_coupon_id IS 'Nullable: orders can be placed without coupons.';
COMMENT ON COLUMN orders.delivery_address_id IS 'NOT NULL: shipping address is required.';

CREATE TRIGGER orders_set_updated_at
    BEFORE UPDATE ON orders
    FOR EACH ROW
    EXECUTE FUNCTION set_updated_at();

-- ---------------------------------------------------------------------------
-- 2. order_items — 주문 상품 (PRD §20.2 snapshot-at-create).
-- ---------------------------------------------------------------------------
CREATE TABLE order_items (
    id             BIGSERIAL PRIMARY KEY,
    order_id       BIGINT      NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    product_id     BIGINT      NOT NULL REFERENCES products(id),
    product_option_id BIGINT   NOT NULL REFERENCES product_options(id),
    product_name   VARCHAR(255) NOT NULL,  -- Snapshot: 원본 상품 변경 불관
    option_name    VARCHAR(255) NOT NULL,  -- Snapshot: 원본 옵션 변경 불관
    unit_price     DECIMAL(12, 2) NOT NULL, -- Snapshot: 주문 시점 가격
    quantity       INTEGER      NOT NULL CHECK (quantity > 0),
    total_amount   DECIMAL(12, 2) NOT NULL CHECK (total_amount >= 0),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

COMMENT ON TABLE order_items IS 'Order line items with product snapshot (PRD §20.2).';
COMMENT ON COLUMN order_items.product_name IS 'Snapshot: copied at create time, survives future product edits.';
COMMENT ON COLUMN order_items.option_name IS 'Snapshot: copied at create time, survives future option edits.';
COMMENT ON COLUMN order_items.unit_price IS 'Snapshot: unit price at order time, NOT current product.price.';

-- ---------------------------------------------------------------------------
-- 3. order_price_summaries — 가격 요약 (audit trail).
-- ---------------------------------------------------------------------------
CREATE TABLE order_price_summaries (
    id                BIGSERIAL PRIMARY KEY,
    order_id          BIGINT      NOT NULL UNIQUE REFERENCES orders(id) ON DELETE CASCADE,
    subtotal          DECIMAL(12, 2) NOT NULL CHECK (subtotal >= 0),
    coupon_discount   DECIMAL(12, 2) NOT NULL DEFAULT 0.00 CHECK (coupon_discount >= 0),
    point_discount    DECIMAL(12, 2) NOT NULL DEFAULT 0.00 CHECK (point_discount >= 0),
    shipping_fee      DECIMAL(12, 2) NOT NULL CHECK (shipping_fee >= 0),
    grand_total       DECIMAL(12, 2) NOT NULL CHECK (grand_total >= 0)
);

COMMENT ON TABLE order_price_summaries IS 'Order price summary for audit reproducibility (PRD §7.6).';
COMMENT ON COLUMN order_price_summaries.subtotal IS 'Sum of order_items.total_amount before discounts.';
COMMENT ON COLUMN order_price_summaries.grand_total IS 'Final amount: subtotal - coupon_discount - point_discount + shipping_fee.';

-- ---------------------------------------------------------------------------
-- 4. order_status_histories — 상태 변경 이력 (PRD §16.2 audit).
-- ---------------------------------------------------------------------------
CREATE TABLE order_status_histories (
    id             BIGSERIAL PRIMARY KEY,
    order_id       BIGINT      NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    from_status    VARCHAR(30),
    to_status      VARCHAR(30) NOT NULL,
    reason         VARCHAR(255),
    changed_by_kind VARCHAR(20) NOT NULL CHECK (changed_by_kind IN ('USER', 'ADMIN', 'SYSTEM')),
    changed_by_id  BIGINT,      -- NULL for SYSTEM
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

COMMENT ON TABLE order_status_histories IS 'Order status transition audit log (PRD §16.2).';
COMMENT ON COLUMN order_status_histories.changed_by_kind IS 'Who triggered: USER (member), ADMIN (CS), SYSTEM (auto).';
COMMENT ON COLUMN order_status_histories.changed_by_id IS 'Member ID for USER, Admin ID for ADMIN, NULL for SYSTEM.';

-- ---------------------------------------------------------------------------
-- 5. 인덱스 (AC3 요구사항)
-- ---------------------------------------------------------------------------

-- AC3-1: (member_id, created_at DESC) for list view
CREATE INDEX idx_orders_member_created
    ON orders (member_id, created_at DESC);

-- AC3-2: (status, created_at) for payment-pending expiry batch
CREATE INDEX idx_orders_status_created
    ON orders (status, created_at);

-- AC3-3: (order_no) UNIQUE — 이미 orders 테이블 정의에 포함됨

-- Additional index for order_status_histories queries
CREATE INDEX idx_order_status_histories_order_created
    ON order_status_histories (order_id, created_at DESC);

-- ---------------------------------------------------------------------------
-- 6. order_no 자동 생성 트리거
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION generate_order_no()
RETURNS TRIGGER AS $$
DECLARE
    v_date_part TEXT;
    v_seq_part INTEGER;
    v_order_no VARCHAR(50);
BEGIN
    -- format: ORD + yyyyMMdd + 6자리 시퀀스
    v_date_part := TO_CHAR(CURRENT_DATE, 'YYYYMMDD');
    v_seq_part := nextval('order_no_seq_yyyymmdd')::INTEGER;
    v_order_no := 'ORD' || v_date_part || LPAD(v_seq_part::TEXT, 6, '0');

    NEW.order_no := v_order_no;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER orders_generate_order_no
    BEFORE INSERT ON orders
    FOR EACH ROW
    EXECUTE FUNCTION generate_order_no();

COMMENT ON FUNCTION generate_order_no() IS 'Auto-generate order_no: ORD<yyyyMMdd><6-digit-seq>. Resets daily via sequence CYCLE.';
