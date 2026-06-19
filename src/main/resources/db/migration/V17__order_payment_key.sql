-- V17: Persist the PG payment key on the order so a cancel can reverse the charge (M1-order-payment-integrity).
-- Idempotent via IF NOT EXISTS — safe to re-run against a schema that already has the column.

ALTER TABLE orders
    ADD COLUMN IF NOT EXISTS payment_key VARCHAR(255);

COMMENT ON COLUMN orders.payment_key IS 'PG payment key copied at approval time. Used to call PG cancel/refund when the order is canceled.';
