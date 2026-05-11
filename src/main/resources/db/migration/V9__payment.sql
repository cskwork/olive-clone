-- OLV-070 Payment domain schema (PRD §6.6, §7.7, §14.4, §20.4).
-- 본 파일은 applied 후 절대 수정 금지 (Hard rule). 후속 변경은 V10+ 로 추가.

-- ---------------------------------------------------------------------------
-- 0. 공용 유틸리티 함수 (updated_at 트리거)
-- ---------------------------------------------------------------------------
-- V7에서 이미 정의됨: set_updated_at()

-- ---------------------------------------------------------------------------
-- 1. payments — 결제 본 테이블 (PRD §7.7).
-- ---------------------------------------------------------------------------
CREATE TABLE payments (
    id                  BIGSERIAL PRIMARY KEY,
    order_id            BIGINT       NOT NULL UNIQUE REFERENCES orders(id),
    payment_key         VARCHAR(255),     -- PG-side unique key, NULLABLE until PG returns one
    pg_provider         VARCHAR(50),      -- toss, kcp, naverpay, etc.
    method              VARCHAR(50)  NOT NULL,
    status              VARCHAR(30)  NOT NULL DEFAULT 'READY',
    requested_amount    DECIMAL(12,2) NOT NULL CHECK (requested_amount >= 0),
    approved_amount     DECIMAL(12,2) CHECK (approved_amount >= 0),
    idempotency_key     UUID         NOT NULL UNIQUE,
    requested_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    approved_at         TIMESTAMPTZ,
    failed_reason       VARCHAR(255),
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT payments_status_check
        CHECK (status IN (
            'READY', 'REQUESTED', 'APPROVED', 'FAILED', 'CANCELED', 'REFUNDED'
        )),
    CONSTRAINT payments_method_check
        CHECK (method IN (
            'CARD', 'KAKAO_PAY', 'NAVER_PAY', 'TOSS_PAY', 'VIRTUAL_ACCOUNT'
        ))
);

COMMENT ON TABLE payments IS 'Payment header (PRD §7.7). Bridges orders to external PG.';
COMMENT ON COLUMN payments.payment_key IS 'PG-side unique key. NULLABLE until PG returns one after approve.';
COMMENT ON COLUMN payments.idempotency_key IS 'Client-provided idempotency key. UNIQUE prevents duplicate payments (PRD §20.4).';
COMMENT ON COLUMN payments.status IS 'Payment state machine: READY → REQUESTED → APPROVED.';
COMMENT ON COLUMN payments.approved_amount IS 'NULL until PG approves. May differ from requested due to PG fees/partial approval.';
COMMENT ON COLUMN payments.failed_reason IS 'Populated when status=FAILED. For post-mortem debugging.';

CREATE TRIGGER payments_set_updated_at
    BEFORE UPDATE ON payments
    FOR EACH ROW
    EXECUTE FUNCTION set_updated_at();

-- ---------------------------------------------------------------------------
-- 2. payment_transactions — PG 트랜잭션 기록 (PRD §20.4 replay protection).
-- ---------------------------------------------------------------------------
CREATE TABLE payment_transactions (
    id                  BIGSERIAL PRIMARY KEY,
    payment_id          BIGINT       NOT NULL REFERENCES payments(id) ON DELETE CASCADE,
    kind                VARCHAR(30)  NOT NULL,
    pg_response_json    JSONB,
    http_status         INTEGER,
    idempotency_key     UUID,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT payment_transactions_kind_check
        CHECK (kind IN (
            'REQUEST', 'APPROVE', 'CANCEL', 'WEBHOOK', 'REFUND'
        )),
    CONSTRAINT payment_transactions_http_status_check
        CHECK (http_status IS NULL OR (http_status >= 100 AND http_status < 600)),
    CONSTRAINT uq_payment_transaction_replay
        UNIQUE (payment_id, kind, idempotency_key)
);

COMMENT ON TABLE payment_transactions IS 'PG transaction log with full response body (PRD §20.4).';
COMMENT ON COLUMN payment_transactions.pg_response_json IS 'Full PG response body as JSONB. Enables post-mortem without enabling extra logging.';
COMMENT ON COLUMN payment_transactions.idempotency_key IS 'Optional. NULL for PG-originated webhooks where client key is unavailable.';
COMMENT ON CONSTRAINT uq_payment_transaction_replay ON payment_transactions IS 'Replay protection: same (payment_id, kind, idempotency_key) cannot be inserted twice.';

-- ---------------------------------------------------------------------------
-- 3. refunds — 환불 테이블 (PRD §7.7).
-- ---------------------------------------------------------------------------
CREATE TABLE refunds (
    id                  BIGSERIAL PRIMARY KEY,
    payment_id          BIGINT       NOT NULL REFERENCES payments(id),
    order_id            BIGINT       NOT NULL REFERENCES orders(id),
    amount              DECIMAL(12,2) NOT NULL CHECK (amount > 0),
    reason              VARCHAR(255),
    status              VARCHAR(30)  NOT NULL DEFAULT 'REQUESTED',
    pg_refund_key       VARCHAR(255),     -- PG-side refund key, NULLABLE until PG returns
    requested_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    approved_at         TIMESTAMPTZ,

    CONSTRAINT refunds_status_check
        CHECK (status IN (
            'REQUESTED', 'APPROVED', 'FAILED'
        ))
);

COMMENT ON TABLE refunds IS 'Refund records (PRD §7.7). One payment can have multiple partial refunds.';
COMMENT ON COLUMN refunds.pg_refund_key IS 'PG-side refund key. NULLABLE until PG approves.';
COMMENT ON COLUMN refunds.status IS 'Refund state: REQUESTED → APPROVED or FAILED.';

-- ---------------------------------------------------------------------------
-- 4. 인덱스
-- ---------------------------------------------------------------------------

-- payments: order_id UNIQUE 이미 테이블 정의에 포함됨
-- payments: idempotency_key UNIQUE 이미 테이블 정의에 포함됨
-- payments: (status, created_at) for payment-pending expiry batch
CREATE INDEX idx_payments_status_created
    ON payments (status, created_at);

-- payments: (payment_key) for PG webhook lookup
CREATE INDEX idx_payments_payment_key
    ON payments (payment_key)
    WHERE payment_key IS NOT NULL;

-- payment_transactions: (payment_id, created_at DESC) for transaction history
CREATE INDEX idx_payment_transactions_payment_created
    ON payment_transactions (payment_id, created_at DESC);

-- refunds: (payment_id) for refund list
CREATE INDEX idx_refunds_payment_id
    ON refunds (payment_id);

-- refunds: (order_id) for order-level refund summary
CREATE INDEX idx_refunds_order_id
    ON refunds (order_id);

-- refunds: (status, requested_at) for refund processing batch
CREATE INDEX idx_refunds_status_requested
    ON refunds (status, requested_at);
