-- OLV-040 Cart domain schema (PRD §6.4, §8.2).
-- 본 파일은 applied 후 절대 수정 금지 (Hard rule). 후속 변경은 V6+ 로 추가.

-- ---------------------------------------------------------------------------
-- 1. carts — 회원 장바구니 본 테이블.
-- ---------------------------------------------------------------------------
CREATE TABLE carts (
    id          BIGSERIAL PRIMARY KEY,
    member_id   BIGINT       NOT NULL UNIQUE REFERENCES members(id) ON DELETE CASCADE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

COMMENT ON TABLE  carts IS 'Member cart (PRD §6.4). One cart per member (UNIQUE member_id).';
COMMENT ON COLUMN carts.member_id IS 'FK to members. ON DELETE CASCADE removes cart when member is deleted.';

CREATE TRIGGER carts_set_updated_at
    BEFORE UPDATE ON carts
    FOR EACH ROW
    EXECUTE FUNCTION set_updated_at();

-- ---------------------------------------------------------------------------
-- 2. cart_items — 장바구니 아이템.
-- ---------------------------------------------------------------------------
CREATE TABLE cart_items (
    id                 BIGSERIAL PRIMARY KEY,
    cart_id            BIGINT       NOT NULL REFERENCES carts(id) ON DELETE CASCADE,
    product_option_id  BIGINT       NOT NULL REFERENCES product_options(id) ON DELETE CASCADE,
    quantity           INTEGER      NOT NULL DEFAULT 1,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT cart_items_quantity_positive CHECK (quantity > 0),
    UNIQUE (cart_id, product_option_id)
);

COMMENT ON TABLE  cart_items IS 'Cart items (PRD §6.4). UNIQUE (cart_id, product_option_id) prevents duplicate entries.';
COMMENT ON COLUMN cart_items.quantity IS 'Item quantity. Must be positive (CHECK constraint).';
COMMENT ON COLUMN cart_items.product_option_id IS 'FK to product_options. ON DELETE CASCADE removes items when option is deleted.';

-- Lookup index for cart → items queries.
CREATE INDEX idx_cart_items_cart_id ON cart_items (cart_id);

-- Lookup index for product_option → cart items queries (e.g., when option is deleted).
CREATE INDEX idx_cart_items_product_option_id ON cart_items (product_option_id);

CREATE TRIGGER cart_items_set_updated_at
    BEFORE UPDATE ON cart_items
    FOR EACH ROW
    EXECUTE FUNCTION set_updated_at();
