-- OLV-W01 Wishlist (찜) domain schema.
-- 본 파일은 applied 후 절대 수정 금지 (Hard rule). 후속 변경은 V20+ 로 추가.

-- ---------------------------------------------------------------------------
-- 1. wishlist_items — 회원 찜 목록.
-- ---------------------------------------------------------------------------
CREATE TABLE wishlist_items (
    id          BIGSERIAL PRIMARY KEY,
    member_id   BIGINT      NOT NULL REFERENCES members(id) ON DELETE CASCADE,
    product_id  BIGINT      NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT wishlist_items_unique UNIQUE (member_id, product_id)
);

COMMENT ON TABLE  wishlist_items IS 'Member wishlist (찜). UNIQUE (member_id, product_id) ensures idempotent add.';
COMMENT ON COLUMN wishlist_items.member_id IS 'FK to members. ON DELETE CASCADE removes entries when member is deleted.';
COMMENT ON COLUMN wishlist_items.product_id IS 'FK to products. ON DELETE CASCADE removes entries when product is deleted.';

-- Lookup index for member → wishlist queries (primary access pattern).
CREATE INDEX idx_wishlist_items_member_id ON wishlist_items (member_id);
