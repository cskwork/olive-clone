-- Deliveries table
CREATE TABLE deliveries (
    id BIGSERIAL PRIMARY KEY,
    order_id BIGINT NOT NULL,
    delivery_address_id BIGINT NOT NULL,
    carrier_name VARCHAR(50) NOT NULL,
    invoice_no VARCHAR(100),
    status VARCHAR(20) NOT NULL DEFAULT 'READY',
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_deliveries_order_id ON deliveries(order_id);
CREATE INDEX idx_deliveries_invoice_no ON deliveries(invoice_no);
CREATE INDEX idx_deliveries_status ON deliveries(status);

-- Delivery status history table
CREATE TABLE delivery_status_histories (
    id BIGSERIAL PRIMARY KEY,
    delivery_id BIGINT NOT NULL,
    from_status VARCHAR(20),
    to_status VARCHAR(20) NOT NULL,
    reason VARCHAR(500),
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_status_histories_delivery_id ON delivery_status_histories(delivery_id);

-- Delivery retry queue table
CREATE TABLE delivery_retry_queue (
    id BIGSERIAL PRIMARY KEY,
    delivery_id BIGINT NOT NULL,
    request_kind VARCHAR(20) NOT NULL,
    payload_json JSONB NOT NULL,
    retry_count INT NOT NULL DEFAULT 0,
    next_retry_at TIMESTAMPTZ NOT NULL,
    last_error TEXT,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_retry_count CHECK (retry_count >= 0 AND retry_count <= 5)
);

CREATE INDEX idx_retry_queue_status ON delivery_retry_queue(status);
CREATE INDEX idx_retry_queue_next_retry_at ON delivery_retry_queue(next_retry_at);

-- ShedLock table for scheduler locking
CREATE TABLE shedlock (
    name VARCHAR(64) NOT NULL,
    lock_until TIMESTAMPTZ NOT NULL,
    locked_at TIMESTAMPTZ NOT NULL,
    locked_by VARCHAR(255) NOT NULL,
    PRIMARY KEY (name)
);
