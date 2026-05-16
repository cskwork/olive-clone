-- OLV-090 Review domain schema (PRD §6.10).
-- 본 파일은 applied 후 절대 수정 금지 (Hard rule). 후속 변경은 V12+ 로 추가.

-- ---------------------------------------------------------------------------
-- 0. 공합 함수 (updated_at 자동 설정)
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION set_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- ---------------------------------------------------------------------------
-- 1. reviews — 리뷰 본 테이블 (PRD §6.10).
-- ---------------------------------------------------------------------------
CREATE TABLE reviews (
    id              BIGSERIAL PRIMARY KEY,
    member_id       BIGINT       NOT NULL REFERENCES members(id),
    product_id      BIGINT       NOT NULL REFERENCES products(id),
    order_item_id   BIGINT       NOT NULL UNIQUE REFERENCES order_items(id),
    rating          SMALLINT     NOT NULL CHECK (rating BETWEEN 1 AND 5),
    title           VARCHAR(255),
    body            TEXT,
    status          VARCHAR(20)  NOT NULL DEFAULT 'VISIBLE' CHECK (status IN ('VISIBLE', 'HIDDEN')),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT reviews_order_item_unique UNIQUE (order_item_id)
);

COMMENT ON TABLE reviews IS 'Customer reviews for purchased items (PRD §6.10).';
COMMENT ON COLUMN reviews.member_id IS 'Review author.';
COMMENT ON COLUMN reviews.product_id IS 'Reviewed product.';
COMMENT ON COLUMN reviews.order_item_id IS 'Purchased order item - UNIQUE prevents duplicate reviews.';
COMMENT ON COLUMN reviews.rating IS '1-5 star rating.';
COMMENT ON COLUMN reviews.status IS 'VISIBLE = shown to public, HIDDEN = admin-hidden.';

CREATE TRIGGER reviews_set_updated_at
    BEFORE UPDATE ON reviews
    FOR EACH ROW
    EXECUTE FUNCTION set_updated_at();

-- 인덱스: 상품별 리뷰 목록 조회 (public API)
CREATE INDEX idx_reviews_product_created
    ON reviews (product_id, created_at DESC)
    WHERE status = 'VISIBLE';

-- 인덱스: 회원별 내 리뷰 목록 조회
CREATE INDEX idx_reviews_member_created
    ON reviews (member_id, created_at DESC);

-- ---------------------------------------------------------------------------
-- 2. review_images — 리뷰 이미지 (PRD §6.10).
-- ---------------------------------------------------------------------------
CREATE TABLE review_images (
    id          BIGSERIAL PRIMARY KEY,
    review_id   BIGINT      NOT NULL REFERENCES reviews(id) ON DELETE CASCADE,
    url         VARCHAR(500) NOT NULL,
    sort_order  INTEGER     NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE review_images IS 'Review images (max N per review).';
COMMENT ON COLUMN review_images.url IS 'S3-compatible image URL.';
COMMENT ON COLUMN review_images.sort_order IS 'Display order; lower first.';

CREATE INDEX idx_review_images_review_order
    ON review_images (review_id, sort_order);

-- ---------------------------------------------------------------------------
-- 3. review_reports — 리뷰 신고 (PRD §6.10).
-- ---------------------------------------------------------------------------
CREATE TABLE review_reports (
    id                  BIGSERIAL PRIMARY KEY,
    review_id           BIGINT      NOT NULL REFERENCES reviews(id),
    reporter_member_id  BIGINT      NOT NULL REFERENCES members(id),
    reason              VARCHAR(255) NOT NULL,
    status              VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'RESOLVED', 'DISMISSED')),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT review_reports_unique_reviewer UNIQUE (review_id, reporter_member_id)
);

COMMENT ON TABLE review_reports IS 'User-reported inappropriate reviews.';
COMMENT ON COLUMN review_reports.status IS 'PENDING = admin action needed, RESOLVED = action taken, DISMISSED = false report.';

-- 인덱스: 관리자 미처리 신고 목록 조회
CREATE INDEX idx_review_reports_status_created
    ON review_reports (status, created_at DESC)
    WHERE status = 'PENDING';

-- ---------------------------------------------------------------------------
-- 4. product_review_summaries — 상품 리뷰 요약 (집계 테이블, PRD §6.10).
-- ---------------------------------------------------------------------------
CREATE TABLE product_review_summaries (
    product_id  BIGINT      PRIMARY KEY REFERENCES products(id),
    avg_rating  DECIMAL(3,2) NOT NULL DEFAULT 0.00 CHECK (avg_rating >= 0 AND avg_rating <= 5),
    review_count INTEGER     NOT NULL DEFAULT 0 CHECK (review_count >= 0),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

COMMENT ON TABLE product_review_summaries IS 'Aggregate review stats per product (hot read path optimization).';
COMMENT ON COLUMN product_review_summaries.avg_rating IS 'Average rating: 0.00-5.00, updated by ReviewCreatedEvent subscriber.';
COMMENT ON COLUMN product_review_summaries.review_count IS 'Total VISIBLE review count.';

CREATE TRIGGER product_review_summaries_set_updated_at
    BEFORE UPDATE ON product_review_summaries
    FOR EACH ROW
    EXECUTE FUNCTION set_updated_at();
