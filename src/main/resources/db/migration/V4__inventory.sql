-- OLV-030 Inventory domain schema (PRD §6.7, §7.4).
-- 본 파일은 applied 후 절대 수정 금지 (Hard rule). 후속 변경은 V5+ 로 추가.

-- ---------------------------------------------------------------------------
-- 1. inventories — 재고 본 테이블 (product_option_id 단위, PRD §20.3).
-- ---------------------------------------------------------------------------
CREATE TABLE inventories (
    id                 BIGSERIAL PRIMARY KEY,
    product_option_id  BIGINT       NOT NULL UNIQUE REFERENCES product_options(id) ON DELETE RESTRICT,
    total_quantity     INTEGER      NOT NULL DEFAULT 0,
    reserved_quantity  INTEGER      NOT NULL DEFAULT 0,
    available_quantity INTEGER      GENERATED ALWAYS AS (total_quantity - reserved_quantity) STORED,
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT inventories_total_non_negative CHECK (total_quantity >= 0),
    CONSTRAINT inventories_reserved_non_negative CHECK (reserved_quantity >= 0),
    CONSTRAINT inventories_total_gte_reserved CHECK (total_quantity >= reserved_quantity)
);

COMMENT ON TABLE  inventories IS 'Per-option inventory (PRD §7.4). One row per product_option_id.';
COMMENT ON COLUMN inventories.product_option_id  IS 'FK to product_options. ON DELETE RESTRICT forces stock-zero workflow before option deletion.';
COMMENT ON COLUMN inventories.total_quantity     IS 'Total physical stock in warehouse.';
COMMENT ON COLUMN inventories.reserved_quantity  IS 'Reserved quantity for pending orders (payment not yet confirmed).';
COMMENT ON COLUMN inventories.available_quantity IS 'Generated column: total - reserved. Automatically recomputed by Postgres (PRD §6.7).';

CREATE TRIGGER inventories_set_updated_at
    BEFORE UPDATE ON inventories
    FOR EACH ROW
    EXECUTE FUNCTION set_updated_at();

-- Lookup index for product_option → inventory queries.
CREATE INDEX idx_inventories_product_option_id ON inventories (product_option_id);

-- ---------------------------------------------------------------------------
-- 2. inventory_histories — 모든 재고 변경 이력 (append-only, PRD §7.4).
-- ---------------------------------------------------------------------------
CREATE TABLE inventory_histories (
    id               BIGSERIAL PRIMARY KEY,
    product_option_id BIGINT       NOT NULL REFERENCES product_options(id) ON DELETE RESTRICT,
    change_type      VARCHAR(20)   NOT NULL,
    quantity_delta   INTEGER       NOT NULL,
    reason           VARCHAR(255),
    order_id         BIGINT,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by       BIGINT,
    CONSTRAINT inventory_histories_change_type_check CHECK (
        change_type IN ('STOCK_IN', 'STOCK_OUT', 'RESERVE', 'COMMIT', 'RELEASE', 'ADMIN_ADJUST')
    )
);

COMMENT ON TABLE  inventory_histories IS 'Audit trail for all inventory changes (PRD §7.4). Append-only; never update/delete rows.';
COMMENT ON COLUMN inventory_histories.change_type    IS 'STOCK_IN | STOCK_OUT | RESERVE | COMMIT | RELEASE | ADMIN_ADJUST';
COMMENT ON COLUMN inventory_histories.quantity_delta IS 'Positive = increase, Negative = decrease. Sign depends on change_type.';
COMMENT ON COLUMN inventory_histories.reason         IS 'Human-readable explanation (e.g., "purchase order #123", "return #456").';
COMMENT ON COLUMN inventory_histories.order_id       IS 'Associated order ID (NULL for admin adjustments).';
COMMENT ON COLUMN inventory_histories.created_by     IS 'Admin user ID (NULL for system-generated changes).';

-- Lookup index for option-specific history.
CREATE INDEX idx_inventory_histories_product_option_id ON inventory_histories (product_option_id);

-- Time-series index for audit queries (recent N changes).
CREATE INDEX idx_inventory_histories_created_at ON inventory_histories (created_at DESC);

-- ---------------------------------------------------------------------------
-- 3. inventory_reservations — 주문별 재고 예약 (TTL 만료 배치용, PRD §7.4, §17.2).
-- ---------------------------------------------------------------------------
CREATE TABLE inventory_reservations (
    id                BIGSERIAL PRIMARY KEY,
    order_id          BIGINT       NOT NULL,
    product_option_id BIGINT       NOT NULL REFERENCES product_options(id) ON DELETE RESTRICT,
    quantity          INTEGER      NOT NULL CHECK (quantity > 0),
    status            VARCHAR(20)  NOT NULL DEFAULT 'HELD',
    expires_at        TIMESTAMPTZ  NOT NULL,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    finalized_at      TIMESTAMPTZ,
    CONSTRAINT inventory_reservations_status_check CHECK (
        status IN ('HELD', 'COMMITTED', 'RELEASED')
    ),
    CONSTRAINT inventory_reservations_status_finalized_consistency CHECK (
        (status = 'HELD' AND finalized_at IS NULL) OR
        (status IN ('COMMITTED', 'RELEASED') AND finalized_at IS NOT NULL)
    )
);

COMMENT ON TABLE  inventory_reservations IS 'Per-order inventory reservations (PRD §7.4). Batch job expires HELD rows where expires_at < now().';
COMMENT ON COLUMN inventory_reservations.status       IS 'HELD (awaiting payment) | COMMITTED (payment approved) | RELEASED (cancelled/expired).';
COMMENT ON COLUMN inventory_reservations.expires_at    IS 'TTL for HELD reservations (default 15 minutes per PRD §6.7).';
COMMENT ON COLUMN inventory_reservations.finalized_at IS 'Set when status transitions to COMMITTED or RELEASED.';

-- AC2: UNIQUE constraint on (order_id, product_option_id) to prevent duplicate reservations.
CREATE UNIQUE INDEX uniq_inventory_reservations_order_option
    ON inventory_reservations (order_id, product_option_id);

-- Batch scan index: find expired HELD reservations (PRD §17.2).
CREATE INDEX idx_inventory_reservations_status_expires
    ON inventory_reservations (status, expires_at);

-- Option-specific lookup: all reservations for an option.
CREATE INDEX idx_inventory_reservations_product_option_id
    ON inventory_reservations (product_option_id);
