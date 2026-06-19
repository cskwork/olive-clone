-- V18: Add sales_count to products for POPULAR sort and ranking (M1-catalog-ranking).
-- Idempotent via IF NOT EXISTS — safe to re-run against a schema that already has the column.

ALTER TABLE products
    ADD COLUMN IF NOT EXISTS sales_count BIGINT NOT NULL DEFAULT 0;

COMMENT ON COLUMN products.sales_count IS 'Cumulative sold quantity (refreshed by SalesAggregationJob). Used for POPULAR sort and ProductRankingJob.';

CREATE INDEX IF NOT EXISTS idx_products_sales_count
    ON products (sales_count DESC, id);
