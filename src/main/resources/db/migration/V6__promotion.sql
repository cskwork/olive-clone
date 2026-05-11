-- OLV-050 Promotion domain schema (PRD §6.8, §7.8).
-- 본 파일은 applied 후 절대 수정 금지 (Hard rule). 후속 변경은 V7+ 로 추가.

-- ---------------------------------------------------------------------------
-- 1. coupons — 쿠폰 마스터 (PRD §7.8).
-- ---------------------------------------------------------------------------
CREATE TABLE coupons (
    id                BIGSERIAL PRIMARY KEY,
    name              VARCHAR(255)  NOT NULL,
    discount_type     VARCHAR(30)   NOT NULL,
    discount_value    DECIMAL(12,2) NOT NULL,
    min_order_amount  DECIMAL(12,2) NULL,
    started_at        TIMESTAMPTZ   NOT NULL,
    ended_at          TIMESTAMPTZ   NOT NULL,
    status            VARCHAR(20)   NOT NULL DEFAULT 'ACTIVE',
    max_issue_count   INTEGER,
    issued_count      INTEGER       NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT coupons_discount_type_check CHECK (
        discount_type IN ('FIXED_AMOUNT', 'PERCENTAGE', 'FREE_SHIPPING', 'BUY_ONE_GET_ONE', 'MEMBER_GRADE')
    ),
    CONSTRAINT coupons_status_check CHECK (status IN ('ACTIVE', 'INACTIVE')),
    CONSTRAINT coupons_discount_value_non_negative CHECK (discount_value >= 0),
    CONSTRAINT coupons_min_order_amount_non_negative CHECK (min_order_amount >= 0),
    CONSTRAINT coupons_max_issue_count_positive CHECK (max_issue_count IS NULL OR max_issue_count > 0),
    CONSTRAINT coupons_issued_count_non_negative CHECK (issued_count >= 0),
    CONSTRAINT coupons_started_before_ended CHECK (started_at <= ended_at),
    CONSTRAINT coupons_issued_count_lte_max CHECK (
        max_issue_count IS NULL OR issued_count <= max_issue_count
    )
);

COMMENT ON TABLE  coupons IS 'Coupon master (PRD §7.8). Defines discount rules for member issuance.';
COMMENT ON COLUMN coupons.discount_type    IS 'FIXED_AMOUNT | PERCENTAGE | FREE_SHIPPING | BUY_ONE_GET_ONE | MEMBER_GRADE';
COMMENT ON COLUMN coupons.discount_value   IS 'Fixed amount (원) or percentage (0-100). Meaning depends on discount_type.';
COMMENT ON COLUMN coupons.min_order_amount IS 'Minimum order amount to apply coupon. NULL = no minimum.';
COMMENT ON COLUMN coupons.max_issue_count  IS 'NULL = unlimited issuance.';
COMMENT ON COLUMN coupons.issued_count    IS 'Current issuance count. Must not exceed max_issue_count when set.';

-- Active coupon lookup index for order validation.
CREATE INDEX idx_coupons_status_period ON coupons (status, started_at, ended_at);

-- ---------------------------------------------------------------------------
-- 2. member_coupons — 회원별 발급 쿠폰 (PRD §7.8).
-- ---------------------------------------------------------------------------
CREATE TABLE member_coupons (
    id            BIGSERIAL PRIMARY KEY,
    member_id     BIGINT       NOT NULL REFERENCES members(id) ON DELETE CASCADE,
    coupon_id     BIGINT       NOT NULL REFERENCES coupons(id) ON DELETE CASCADE,
    status        VARCHAR(20)  NOT NULL DEFAULT 'ISSUED',
    issued_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    used_at       TIMESTAMPTZ,
    used_order_id BIGINT,
    expires_at    TIMESTAMPTZ  NOT NULL,
    CONSTRAINT member_coupons_status_check CHECK (
        status IN ('ISSUED', 'USED', 'EXPIRED', 'REVOKED')
    ),
    CONSTRAINT member_coupons_used_consistency CHECK (
        (status = 'USED' AND used_at IS NOT NULL AND used_order_id IS NOT NULL) OR
        (status != 'USED')
    )
);

COMMENT ON TABLE  member_coupons IS 'Member-issued coupons (PRD §7.8). One row per coupon per member.';
COMMENT ON COLUMN member_coupons.status        IS 'ISSUED (available) | USED (consumed) | EXPIRED (past expires_at) | REVOKED (admin)';
COMMENT ON COLUMN member_coupons.used_order_id IS 'Order that used this coupon. FK defers to orders table (V7).';

-- AC: member_id + status lookup for "my available coupons" queries.
CREATE INDEX idx_member_coupons_member_status ON member_coupons (member_id, status);

-- Expiry batch job scan index: find ISSUED coupons past expires_at.
CREATE INDEX idx_member_coupons_status_expires ON member_coupons (status, expires_at);

-- One-per-member uniqueness: prevent duplicate issuance for same coupon to same member.
-- NOTE: For multi-issue campaigns (max_issue_count > 1 per member), drop this index
-- and rely on application-level logic to track per-member issuance count.
CREATE UNIQUE INDEX uniq_member_coupons_member_coupon
    ON member_coupons (member_id, coupon_id)
    WHERE status = 'ISSUED';

-- ---------------------------------------------------------------------------
-- 3. promotions — 프로모션 캠페인 (기획전, PRD §6.8).
-- ---------------------------------------------------------------------------
CREATE TABLE promotions (
    id                BIGSERIAL PRIMARY KEY,
    name              VARCHAR(255) NOT NULL,
    type              VARCHAR(30)  NOT NULL,
    started_at        TIMESTAMPTZ  NOT NULL,
    ended_at          TIMESTAMPTZ  NOT NULL,
    discount_rule_json JSONB        NOT NULL,
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT promotions_type_check CHECK (
        type IN ('TIME_DEAL', 'EVENT', 'CATEGORY_SALE')
    ),
    CONSTRAINT promotions_started_before_ended CHECK (started_at <= ended_at)
);

COMMENT ON TABLE  promotions IS 'Promotion campaigns (PRD §6.8). Time deals, events, category sales.';
COMMENT ON COLUMN promotions.type              IS 'TIME_DEAL | EVENT | CATEGORY_SALE';
COMMENT ON COLUMN promotions.discount_rule_json IS 'Flexible discount rules (e.g., {"buy": 2, "get": 1, "discount_percent": 50}).';

-- Active promotion lookup index.
CREATE INDEX idx_promotions_type_period ON promotions (type, started_at, ended_at);

-- ---------------------------------------------------------------------------
-- 4. promotion_products — 프로모션-상품 매핑 (PRD §6.8).
-- ---------------------------------------------------------------------------
CREATE TABLE promotion_products (
    promotion_id BIGINT NOT NULL REFERENCES promotions(id) ON DELETE CASCADE,
    product_id   BIGINT NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    PRIMARY KEY (promotion_id, product_id)
);

COMMENT ON TABLE  promotion_products IS 'Many-to-many promotion ↔ products (PRD §6.8). All products in a promotion share the same discount_rule_json.';

-- Lookup index: all promotions for a product.
CREATE INDEX idx_promotion_products_product_id ON promotion_products (product_id);

-- ---------------------------------------------------------------------------
-- 5. points — 회원 포인트 잔액 캐시 (PRD §6.8, §7.8).
-- ---------------------------------------------------------------------------
CREATE TABLE points (
    id        BIGSERIAL PRIMARY KEY,
    member_id BIGINT       NOT NULL UNIQUE REFERENCES members(id) ON DELETE CASCADE,
    balance   DECIMAL(12,2) NOT NULL DEFAULT 0.00,
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT points_balance_non_negative CHECK (balance >= 0)
);

COMMENT ON TABLE  points IS 'Cached point balance per member (PRD §6.8). Convenience column; source of truth is point_histories.';
COMMENT ON COLUMN points.balance IS 'Cached sum of available point_histories. Can be recomputed via: SUM(amount) WHERE member_id = ? AND available_at <= now() AND (expires_at IS NULL OR expires_at > now()).';

-- Balance lookup index (member_id UNIQUE already creates b-tree).

-- Recompute trigger: balance always equals sum of available history.
CREATE OR REPLACE FUNCTION update_points_balance()
RETURNS TRIGGER AS $$
DECLARE
    available_balance DECIMAL(12,2);
BEGIN
    IF TG_OP = 'INSERT' THEN
        -- Compute new balance for the member.
        SELECT COALESCE(SUM(
            CASE
                WHEN change_type IN ('EARN', 'ADMIN_ADJUST') THEN amount
                WHEN change_type IN ('USE', 'CANCEL', 'EXPIRE') THEN -amount
                ELSE 0
            END
        ), 0.00)
        INTO available_balance
        FROM point_histories
        WHERE member_id = NEW.member_id
          AND available_at <= now()
          AND (expires_at IS NULL OR expires_at > now());

        -- Insert or update points row.
        INSERT INTO points (member_id, balance)
        VALUES (NEW.member_id, available_balance)
        ON CONFLICT (member_id) DO UPDATE
        SET balance = EXCLUDED.balance,
            updated_at = now();

        RETURN NEW;
    ELSE
        -- For UPDATE/DELETE, recompute for the affected member.
        SELECT COALESCE(SUM(
            CASE
                WHEN change_type IN ('EARN', 'ADMIN_ADJUST') THEN amount
                WHEN change_type IN ('USE', 'CANCEL', 'EXPIRE') THEN -amount
                ELSE 0
            END
        ), 0.00)
        INTO available_balance
        FROM point_histories
        WHERE member_id = COALESCE(NEW.member_id, OLD.member_id)
          AND available_at <= now()
          AND (expires_at IS NULL OR expires_at > now());

        UPDATE points
        SET balance = available_balance,
            updated_at = now()
        WHERE member_id = COALESCE(NEW.member_id, OLD.member_id);

        RETURN COALESCE(NEW, OLD);
    END IF;
END;
$$ LANGUAGE plpgsql;

-- Attach trigger to point_histories (created after table definition below).

-- ---------------------------------------------------------------------------
-- 6. point_histories — 포인트 원장 (PRD §7.8).
-- ---------------------------------------------------------------------------
CREATE TABLE point_histories (
    id           BIGSERIAL PRIMARY KEY,
    member_id    BIGINT       NOT NULL REFERENCES members(id) ON DELETE CASCADE,
    change_type  VARCHAR(20)  NOT NULL,
    amount       DECIMAL(12,2) NOT NULL,
    reason       VARCHAR(255),
    order_id     BIGINT,
    available_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at   TIMESTAMPTZ,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT point_histories_change_type_check CHECK (
        change_type IN ('EARN', 'USE', 'CANCEL', 'EXPIRE', 'ADMIN_ADJUST')
    ),
    CONSTRAINT point_histories_amount_positive CHECK (amount > 0),
    CONSTRAINT point_histories_available_before_expiry CHECK (
        expires_at IS NULL OR available_at <= expires_at
    )
);

COMMENT ON TABLE  point_histories IS 'Point ledger (PRD §7.8). Source of truth for member point balance. Append-only; never update/delete.';
COMMENT ON COLUMN point_histories.change_type  IS 'EARN (적립) | USE (사용) | CANCEL (사용 취소) | EXPIRE (소멸) | ADMIN_ADJUST (관리자 조정)';
COMMENT ON COLUMN point_histories.amount       IS 'Always positive. Sign implied by change_type.';
COMMENT ON COLUMN point_histories.reason       IS 'Human-readable explanation (e.g., "주문 #123 적립", "관리자 수동 조정").';
COMMENT ON COLUMN point_histories.order_id     IS 'Associated order ID. NULL for adjustments/expirations.';
COMMENT ON COLUMN point_histories.available_at IS 'When points become spendable. Can be future (배송 완료 후 N일 적립).';
COMMENT ON COLUMN point_histories.expires_at   IS 'Points expire after this date. NULL = no expiry (기본 적립금 등).';

-- AC: member_id + time-range lookup for balance computation and history queries.
CREATE INDEX idx_point_histories_member_available_expires
    ON point_histories (member_id, available_at, expires_at);

-- Order lookup: all point changes for an order.
CREATE INDEX idx_point_histories_order_id ON point_histories (order_id);

-- Expiry batch job scan: find EARN rows that will expire soon.
CREATE INDEX idx_point_histories_expires_at
    ON point_histories (expires_at)
    WHERE expires_at IS NOT NULL;

-- Attach balance update trigger.
CREATE TRIGGER point_histories_update_points_balance
    AFTER INSERT OR UPDATE OR DELETE ON point_histories
    FOR EACH ROW
    EXECUTE FUNCTION update_points_balance();

-- ---------------------------------------------------------------------------
-- 7. Seed data: demo coupons, promotion.
-- ---------------------------------------------------------------------------

-- Demo coupon: 3000원 정액 할인 (최소 주문 10000원).
INSERT INTO coupons (name, discount_type, discount_value, min_order_amount, started_at, ended_at, max_issue_count) VALUES
    ('신규 회원 3000원 쿠폰', 'FIXED_AMOUNT', 3000, 10000,
     now(), now() + INTERVAL '90 days', 10000);

-- Demo percentage coupon: 10% 할인 (최대 5000원, 최소 30000원).
INSERT INTO coupons (name, discount_type, discount_value, min_order_amount, started_at, ended_at, max_issue_count) VALUES
    ('10% 할인 쿠폰 (최대 5000원)', 'PERCENTAGE', 10, 30000,
     now(), now() + INTERVAL '90 days', 5000);

-- Demo free shipping coupon.
INSERT INTO coupons (name, discount_type, discount_value, min_order_amount, started_at, ended_at, max_issue_count) VALUES
    ('무료 배송 쿠폰', 'FREE_SHIPPING', 0, 15000,
     now(), now() + INTERVAL '90 days', 20000);

-- Demo BOGO coupon: "1+1" (buy 1, get 1 free) on specific products.
INSERT INTO coupons (name, discount_type, discount_value, min_order_amount, started_at, ended_at, max_issue_count) VALUES
    ('선크림 1+1 쿠폰', 'BUY_ONE_GET_ONE', 100, 0,
     now(), now() + INTERVAL '90 days', 1000);

-- Demo promotion: TIME_DEAL (특가).
INSERT INTO promotions (name, type, started_at, ended_at, discount_rule_json) VALUES
    ('여름 선크림 특가', 'TIME_DEAL', now(), now() + INTERVAL '30 days',
     '{"sale_rate": 50}'::jsonb);

-- Demo promotion products: link the demo product to the TIME_DEAL.
INSERT INTO promotion_products (promotion_id, product_id)
SELECT p.id, pr.id
FROM promotions p
CROSS JOIN (SELECT id FROM products WHERE name LIKE '%선크림%' LIMIT 1) pr
WHERE p.name = '여름 선크림 특가'
ON CONFLICT DO NOTHING;
