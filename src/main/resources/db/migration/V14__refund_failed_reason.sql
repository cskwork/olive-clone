-- OLV-074: persist refund failure reason for rejected PG/admin refund attempts.
ALTER TABLE refunds
    ADD COLUMN failed_reason VARCHAR(255);

COMMENT ON COLUMN refunds.failed_reason IS 'Reason populated when a refund is rejected or fails.';
