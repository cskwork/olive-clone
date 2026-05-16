-- OLV-120 Batch jobs domain schema (PRD §17).
-- 본 파일은 applied 후 절대 수정 금지 (Hard rule). 후속 변경은 V13+ 로 추가.

-- ---------------------------------------------------------------------------
-- 0. 공용 유틸리티 함수
-- ---------------------------------------------------------------------------
-- updated_at 자동 설정 트리거 (V11에서 이미 정의됨)

-- ---------------------------------------------------------------------------
-- 1. job_runs — 배치 작업 실행 기록 (PRD §17).
-- ---------------------------------------------------------------------------
CREATE TABLE job_runs (
    id               BIGSERIAL PRIMARY KEY,
    job_name         VARCHAR(100) NOT NULL,
    started_at       TIMESTAMPTZ   NOT NULL,
    finished_at      TIMESTAMPTZ,
    status           VARCHAR(20)   NOT NULL,
    processed_count  INTEGER       NOT NULL DEFAULT 0,
    error_message    TEXT,
    triggered_by     VARCHAR(50)   NOT NULL DEFAULT 'SCHEDULED',
    CONSTRAINT job_runs_status_check CHECK (
        status IN ('STARTED', 'COMPLETED', 'FAILED')
    ),
    CONSTRAINT job_runs_triggered_by_check CHECK (
        triggered_by IN ('SCHEDULED', 'MANUAL')
    )
);

COMMENT ON TABLE job_runs IS 'Batch job execution log (PRD §17). Tracks every scheduled/manual run for observability.';
COMMENT ON COLUMN job_runs.job_name        IS 'Job identifier (e.g., paymentPendingExpiry, inventoryReservationExpiry).';
COMMENT ON COLUMN job_runs.status          IS 'STARTED (running) | COMPLETED (success) | FAILED (error).';
COMMENT ON COLUMN job_runs.processed_count IS 'Number of rows/entities processed by this run.';
COMMENT ON COLUMN job_runs.triggered_by    IS 'SCHEDULED (cron trigger) | MANUAL (admin API call).';

-- 인덱스: 작업별 실행 이력 조회 (최신순)
CREATE INDEX idx_job_runs_name_started
    ON job_runs (job_name, started_at DESC);

-- 인덱스: 상태별 조회 (실패한 작업 모니터링)
CREATE INDEX idx_job_runs_status
    ON job_runs (status, started_at DESC)
    WHERE status = 'FAILED';

-- ---------------------------------------------------------------------------
-- 2. daily_sales_summaries — 일별 매출 집계 (PRD §17).
-- ---------------------------------------------------------------------------
CREATE TABLE daily_sales_summaries (
    id                  BIGSERIAL PRIMARY KEY,
    summary_date        DATE         NOT NULL,
    category_id         BIGINT,
    brand_id            BIGINT,
    product_id          BIGINT,
    order_count         INTEGER      NOT NULL DEFAULT 0,
    total_sales_amount  DECIMAL(12,2) NOT NULL DEFAULT 0.00,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT daily_sales_summaries_date_check CHECK (
        summary_date = DATE_TRUNC('day', summary_date)
    ),
    CONSTRAINT daily_sales_summaries_non_negative CHECK (
        order_count >= 0 AND total_sales_amount >= 0
    )
);

COMMENT ON TABLE daily_sales_summaries IS 'Daily sales aggregation by category/brand/product (PRD §17). Computed by SalesAggregationJob.';
COMMENT ON COLUMN daily_sales_summaries.summary_date   IS 'Date being aggregated (date-truncated).';
COMMENT ON COLUMN daily_sales_summaries.category_id    IS 'Optional: filter by category.';
COMMENT ON COLUMN daily_sales_summaries.brand_id       IS 'Optional: filter by brand.';
COMMENT ON COLUMN daily_sales_summaries.product_id     IS 'Optional: filter by product (most granular).';
COMMENT ON COLUMN daily_sales_summaries.order_count    IS 'Number of paid orders for this date/dimension.';
COMMENT ON COLUMN daily_sales_summaries.total_sales_amount IS 'Sum of final_payment_amount for paid orders.';

-- 복합 유니크 제약: 같은 날짜와 차원 조합에 대해 중복 방지
-- NULL 값은 유니크 제약에서 서로 다른 값으로 취급되므로, COALESCE로 처리
CREATE UNIQUE INDEX uniq_daily_sales_summary
    ON daily_sales_summaries (
        summary_date,
        COALESCE(category_id, -1),
        COALESCE(brand_id, -1),
        COALESCE(product_id, -1)
    );

-- 인덱스: 날짜 범위 조회 (대시보드)
CREATE INDEX idx_daily_sales_summaries_date
    ON daily_sales_summaries (summary_date DESC);

-- 인덱스: 카테고리별 집계 조회
CREATE INDEX idx_daily_sales_summaries_category
    ON daily_sales_summaries (category_id, summary_date DESC)
    WHERE category_id IS NOT NULL;

-- 인덱스: 브랜드별 집계 조회
CREATE INDEX idx_daily_sales_summaries_brand
    ON daily_sales_summaries (brand_id, summary_date DESC)
    WHERE brand_id IS NOT NULL;

-- 인덱스: 상품별 집계 조회
CREATE INDEX idx_daily_sales_summaries_product
    ON daily_sales_summaries (product_id, summary_date DESC)
    WHERE product_id IS NOT NULL;

-- updated_at 자동 설정 트리거
CREATE TRIGGER daily_sales_summaries_set_updated_at
    BEFORE UPDATE ON daily_sales_summaries
    FOR EACH ROW
    EXECUTE FUNCTION set_updated_at();

-- ---------------------------------------------------------------------------
-- 3. product_rankings — 상품 랭킹 (PRD §17).
-- ---------------------------------------------------------------------------
CREATE TABLE product_rankings (
    id              BIGSERIAL PRIMARY KEY,
    product_id      BIGINT       NOT NULL UNIQUE REFERENCES products(id),
    rank_score      DECIMAL(10,4) NOT NULL,
    sales_count     INTEGER      NOT NULL DEFAULT 0,
    review_count    INTEGER      NOT NULL DEFAULT 0,
    avg_rating      DECIMAL(3,2)  NOT NULL DEFAULT 0.00,
    computed_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT product_rankings_score_non_negative CHECK (rank_score >= 0),
    CONSTRAINT product_rankings_avg_rating_check CHECK (
        avg_rating >= 0 AND avg_rating <= 5
    )
);

COMMENT ON TABLE product_rankings IS 'Product ranking table (PRD §17). Computed hourly by ProductRankingJob.';
COMMENT ON COLUMN product_rankings.rank_score   IS 'Weighted score: sales_count * 0.5 + review_count * 0.3 + avg_rating * 0.2.';
COMMENT ON COLUMN product_rankings.sales_count  IS 'Total number of orders for this product.';
COMMENT ON COLUMN product_rankings.review_count IS 'Total VISIBLE review count.';
COMMENT ON COLUMN product_rankings.avg_rating   IS 'Average rating from product_review_summaries.';
COMMENT ON COLUMN product_rankings.computed_at  IS 'When this ranking was last recomputed.';

-- 인덱스: 랭킹 점숫값 기준 정렬 (Top-N 조회)
CREATE INDEX idx_product_rankings_score
    ON product_rankings (rank_score DESC, product_id);

-- 인덱스: 판매량 기준 정렬
CREATE INDEX idx_product_rankings_sales
    ON product_rankings (sales_count DESC, product_id);

-- 인덱스: 재계산 시간 기준 조회
CREATE INDEX idx_product_rankings_computed
    ON product_rankings (computed_at DESC);
