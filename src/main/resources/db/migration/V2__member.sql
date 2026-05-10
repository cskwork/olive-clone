-- OLV-010 Member domain schema (PRD §6.1, §7.1, §14).
-- 본 파일은 applied 후 절대 수정 금지 (Hard rule). 후속 변경은 V3+ 로 추가.

-- ---------------------------------------------------------------------------
-- 0. updated_at 자동 갱신 트리거 (재사용 가능)
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION set_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- ---------------------------------------------------------------------------
-- 1. member_grades — 회원 등급. members.grade_id 가 참조하므로 먼저 생성.
-- ---------------------------------------------------------------------------
CREATE TABLE member_grades (
    id                  BIGSERIAL PRIMARY KEY,
    name                VARCHAR(50)    NOT NULL UNIQUE,
    discount_rate       DECIMAL(5, 2)  NOT NULL DEFAULT 0.00,
    point_rate          DECIMAL(5, 2)  NOT NULL DEFAULT 0.00,
    benefit_description TEXT,
    sort_order          INTEGER        NOT NULL DEFAULT 0
);

COMMENT ON TABLE  member_grades IS 'Membership grade tier (BRONZE / SILVER / GOLD).';
COMMENT ON COLUMN member_grades.discount_rate IS 'Order-time discount percentage (0.00 ~ 100.00).';
COMMENT ON COLUMN member_grades.point_rate    IS 'Earned-point percentage on paid amount.';

INSERT INTO member_grades (name, discount_rate, point_rate, benefit_description, sort_order) VALUES
    ('BRONZE', 0.00, 1.00, '기본 등급 — 결제 금액의 1% 적립.',                     1),
    ('SILVER', 2.00, 2.00, '누적 구매 30만원 이상 — 2% 할인 + 2% 적립.',           2),
    ('GOLD',   5.00, 3.00, '누적 구매 100만원 이상 — 5% 할인 + 3% 적립 + 무료배송.', 3);

-- ---------------------------------------------------------------------------
-- 2. members — PRD §7.1 회원 본 테이블.
-- ---------------------------------------------------------------------------
CREATE TABLE members (
    id            BIGSERIAL PRIMARY KEY,
    email         VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    name          VARCHAR(100) NOT NULL,
    phone         VARCHAR(20),
    status        VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    grade_id      BIGINT       NOT NULL REFERENCES member_grades(id),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT members_status_check CHECK (status IN ('ACTIVE', 'SUSPENDED', 'DELETED'))
);

COMMENT ON TABLE  members IS 'User account (PRD §7.1). password_hash uses bcrypt cost ≥ 12 (PRD §14.3).';
COMMENT ON COLUMN members.email  IS 'Unique login id. UNIQUE constraint auto-creates b-tree index.';
COMMENT ON COLUMN members.status IS 'ACTIVE | SUSPENDED | DELETED. Lock state lives in Redis (PRD §14).';

CREATE TRIGGER members_set_updated_at
    BEFORE UPDATE ON members
    FOR EACH ROW
    EXECUTE FUNCTION set_updated_at();

-- ---------------------------------------------------------------------------
-- 3. member_addresses — 배송지 (PRD §6.1). PII 분리 보관.
-- ---------------------------------------------------------------------------
CREATE TABLE member_addresses (
    id              BIGSERIAL PRIMARY KEY,
    member_id       BIGINT       NOT NULL REFERENCES members(id) ON DELETE CASCADE,
    recipient_name  VARCHAR(100) NOT NULL,
    phone           VARCHAR(20)  NOT NULL,
    zipcode         VARCHAR(10)  NOT NULL,
    address_main    VARCHAR(255) NOT NULL,
    address_detail  VARCHAR(255),
    is_default      BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

COMMENT ON TABLE member_addresses IS 'Member shipping address. PII separated from members per PRD §14.3.';

CREATE INDEX idx_member_addresses_member_id ON member_addresses (member_id);

-- 회원당 default 배송지는 1건만 — partial unique index.
CREATE UNIQUE INDEX uniq_member_addresses_default_per_member
    ON member_addresses (member_id)
    WHERE is_default = TRUE;

-- ---------------------------------------------------------------------------
-- 4. member_login_histories — 로그인 감사 (PRD §16.2).
-- ---------------------------------------------------------------------------
CREATE TABLE member_login_histories (
    id              BIGSERIAL PRIMARY KEY,
    member_id       BIGINT       REFERENCES members(id) ON DELETE SET NULL,
    login_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    ip_address      VARCHAR(45),
    user_agent      VARCHAR(512),
    success         BOOLEAN      NOT NULL,
    failure_reason  VARCHAR(50)
);

COMMENT ON TABLE  member_login_histories IS 'Login attempt audit trail (PRD §16.2). member_id NULL allowed for unknown-email failures.';
COMMENT ON COLUMN member_login_histories.failure_reason IS 'BAD_CREDENTIALS | ACCOUNT_LOCKED | UNKNOWN_EMAIL when success=false.';

-- 감사 조회 패턴: 특정 회원의 최근 로그인 N건 → (member_id, login_at DESC).
CREATE INDEX idx_member_login_histories_member_recent
    ON member_login_histories (member_id, login_at DESC);

-- ---------------------------------------------------------------------------
-- 5. member_refresh_tokens — JWT refresh token 저장 (OLV-005 contract).
-- ---------------------------------------------------------------------------
CREATE TABLE member_refresh_tokens (
    id          BIGSERIAL PRIMARY KEY,
    member_id   BIGINT       NOT NULL REFERENCES members(id) ON DELETE CASCADE,
    token_hash  CHAR(64)     NOT NULL UNIQUE,
    issued_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at  TIMESTAMPTZ  NOT NULL,
    revoked_at  TIMESTAMPTZ
);

COMMENT ON TABLE  member_refresh_tokens IS 'Refresh token store (PRD §14.1). token_hash = SHA-256 hex of opaque token.';
COMMENT ON COLUMN member_refresh_tokens.token_hash IS 'SHA-256 hex (64 chars). Plain token never persisted.';

CREATE INDEX idx_member_refresh_tokens_member_active
    ON member_refresh_tokens (member_id)
    WHERE revoked_at IS NULL;
