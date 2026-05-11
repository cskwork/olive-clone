-- OLV-020 Product domain schema (PRD §6.2, §7.2-7.3).
-- 본 파일은 applied 후 절대 수정 금지 (Hard rule). 후속 변경은 V4+ 로 추가.

-- ---------------------------------------------------------------------------
-- 1. brands — 브랜드 마스터. products.brand_id 가 참조하므로 먼저 생성.
-- ---------------------------------------------------------------------------
CREATE TABLE brands (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(100) NOT NULL UNIQUE,
    slug        VARCHAR(100) NOT NULL UNIQUE,
    logo_url    VARCHAR(512),
    status      VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT brands_status_check CHECK (status IN ('ACTIVE', 'INACTIVE'))
);

COMMENT ON TABLE  brands IS 'Brand master (PRD §7.2). name UNIQUE enables brand lookup.';
COMMENT ON COLUMN brands.slug    IS 'URL-friendly brand identifier (e.g., "innisfree").';
COMMENT ON COLUMN brands.logo_url IS 'S3-compatible object storage URL.';

CREATE TRIGGER brands_set_updated_at
    BEFORE UPDATE ON brands
    FOR EACH ROW
    EXECUTE FUNCTION set_updated_at();

-- ---------------------------------------------------------------------------
-- 2. categories — 카테고리 계층 구조 (self-referencing FK).
-- ---------------------------------------------------------------------------
CREATE TABLE categories (
    id          BIGSERIAL PRIMARY KEY,
    parent_id   BIGINT       REFERENCES categories(id) ON DELETE SET NULL,
    name        VARCHAR(100) NOT NULL,
    slug        VARCHAR(100) NOT NULL,
    sort_order  INTEGER      NOT NULL DEFAULT 0,
    depth       INTEGER      NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

COMMENT ON TABLE  categories IS 'Category hierarchy (PRD §7.3). parent_id NULL = top-level.';
COMMENT ON COLUMN categories.depth IS 'Tree depth (0 = root, 1 = child, 2 = grandchild).';

-- Top-level name uniqueness per PRD §7.3 (category names may repeat under different parents).
CREATE UNIQUE INDEX uniq_categories_name_per_parent
    ON categories (name, parent_id)
    WHERE parent_id IS NULL;

-- Lookup indexes for category tree navigation.
CREATE INDEX idx_categories_parent_id ON categories (parent_id);
CREATE INDEX idx_categories_depth ON categories (depth);

CREATE TRIGGER categories_set_updated_at
    BEFORE UPDATE ON categories
    FOR EACH ROW
    EXECUTE FUNCTION set_updated_at();

-- ---------------------------------------------------------------------------
-- 3. products — 상품 본 테이블 (PRD §6.2, §7.2).
-- ---------------------------------------------------------------------------
CREATE TABLE products (
    id          BIGSERIAL PRIMARY KEY,
    brand_id    BIGINT       REFERENCES brands(id) ON DELETE SET NULL,
    name        VARCHAR(255) NOT NULL,
    description TEXT,
    status      VARCHAR(20)  NOT NULL DEFAULT 'DRAFT',
    base_price  DECIMAL(12, 2) NOT NULL,
    sale_price  DECIMAL(12, 2),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT products_status_check CHECK (
        status IN ('DRAFT', 'ON_SALE', 'SOLD_OUT', 'STOPPED', 'HIDDEN')
    ),
    CONSTRAINT products_base_price_non_negative CHECK (base_price >= 0),
    CONSTRAINT products_sale_price_non_negative CHECK (sale_price >= 0)
);

COMMENT ON TABLE  products IS 'Product master (PRD §7.2). brand_id NULLABLE for unbranded products.';
COMMENT ON COLUMN products.status IS 'DRAFT | ON_SALE | SOLD_OUT | STOPPED | HIDDEN (PRD §6.2).';
COMMENT ON COLUMN products.base_price IS 'Original price (never NULL).';
COMMENT ON COLUMN products.sale_price IS 'Discounted price (NULL = no sale).';

-- Primary indexes per AC.
CREATE INDEX idx_products_status_brand ON products (status, brand_id);
CREATE INDEX idx_products_name_pattern ON products (name text_pattern_ops);

CREATE TRIGGER products_set_updated_at
    BEFORE UPDATE ON products
    FOR EACH ROW
    EXECUTE FUNCTION set_updated_at();

-- ---------------------------------------------------------------------------
-- 4. product_options — 옵션 (색상, 용량, 구성 등). 재고는 여기에 연결.
-- ---------------------------------------------------------------------------
CREATE TABLE product_options (
    id            BIGSERIAL PRIMARY KEY,
    product_id    BIGINT       NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    option_name   VARCHAR(100) NOT NULL,
    option_price  DECIMAL(12, 2) NOT NULL DEFAULT 0.00,
    status        VARCHAR(20)  NOT NULL DEFAULT 'ON_SALE',
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT product_options_status_check CHECK (
        status IN ('ON_SALE', 'SOLD_OUT', 'STOPPED', 'HIDDEN')
    ),
    CONSTRAINT product_options_option_price_non_negative CHECK (option_price >= 0)
);

COMMENT ON TABLE  product_options IS 'Product option (color, volume, set). Inventory lives here (PRD §20.3).';
COMMENT ON COLUMN product_options.option_name IS 'Human-readable option label (e.g., "1호 색상").';
COMMENT ON COLUMN product_options.option_price IS 'Additional price over base_price.';

CREATE INDEX idx_product_options_product_id ON product_options (product_id);

CREATE TRIGGER product_options_set_updated_at
    BEFORE UPDATE ON product_options
    FOR EACH ROW
    EXECUTE FUNCTION set_updated_at();

-- ---------------------------------------------------------------------------
-- 5. product_images — 상품 이미지 URL.
-- ---------------------------------------------------------------------------
CREATE TABLE product_images (
    id            BIGSERIAL PRIMARY KEY,
    product_id    BIGINT       NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    url           VARCHAR(512) NOT NULL,
    sort_order    INTEGER      NOT NULL DEFAULT 0,
    is_thumbnail  BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

COMMENT ON TABLE  product_images IS 'Product image URLs (PRD §6.2). Actual images live in S3-compatible storage.';
COMMENT ON COLUMN product_images.is_thumbnail IS 'Thumbnail flag (one per product typically).';

CREATE INDEX idx_product_images_product_sort ON product_images (product_id, sort_order);

-- ---------------------------------------------------------------------------
-- 6. product_category_mapping — M:N 매핑 (한 제품은 여러 카테고리에 속함).
-- ---------------------------------------------------------------------------
CREATE TABLE product_category_mapping (
    product_id  BIGINT NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    category_id BIGINT NOT NULL REFERENCES categories(id) ON DELETE CASCADE,
    PRIMARY KEY (product_id, category_id)
);

COMMENT ON TABLE  product_category_mapping IS 'Many-to-many product ↔ categories (PRD §7.3).';

-- ---------------------------------------------------------------------------
-- Seed data: demo brand, categories, product with options.
-- ---------------------------------------------------------------------------

-- Demo brand.
INSERT INTO brands (id, name, slug, logo_url, status) VALUES
    (1, '더샘', 'thesecret', 'https://s3.local/brands/thesecret.png', 'ACTIVE');

-- Top-level categories (PRD §7.3).
INSERT INTO categories (id, name, slug, parent_id, sort_order, depth) VALUES
    (1, '스킨케어', 'skincare', NULL, 1, 0),
    (2, '메이크업', 'makeup', NULL, 2, 0),
    (3, '헤어/바디', 'hair-body', NULL, 3, 0);

-- Demo product: 선크림 (base_price 25000, sale_price 20000).
INSERT INTO products (brand_id, name, description, status, base_price, sale_price) VALUES
    ((SELECT id FROM brands WHERE slug = 'thesecret'),
     '키즈 매일 선크림 SPF50+ PA++++',
     '어린이 피부에 안전한 무기자차 선크림으로, 물놀이나 수영 후에도 씻어낼 필요가 없습니다.',
     'ON_SALE',
     25000,
     20000);

-- Demo product options: 50ml, 100ml.
INSERT INTO product_options (product_id, option_name, option_price, status) VALUES
    ((SELECT id FROM products WHERE name LIKE '%선크림%'), '50ml', 0, 'ON_SALE'),
    ((SELECT id FROM products WHERE name LIKE '%선크림%'), '100ml', 5000, 'ON_SALE');

-- Demo product images.
INSERT INTO product_images (product_id, url, sort_order, is_thumbnail) VALUES
    ((SELECT id FROM products WHERE name LIKE '%선크림%'),
     'https://s3.local/products/suncream-thumb.png', 1, TRUE),
    ((SELECT id FROM products WHERE name LIKE '%선크림%'),
     'https://s3.local/products/suncream-1.png', 2, FALSE),
    ((SELECT id FROM products WHERE name LIKE '%선크림%'),
     'https://s3.local/products/suncream-2.png', 3, FALSE);

-- Demo product → categories mapping (스킨케어 + 하위).
INSERT INTO product_category_mapping (product_id, category_id)
SELECT p.id, c.id
FROM products p
CROSS JOIN categories c
WHERE p.name LIKE '%선크림%'
  AND c.name IN ('스킨케어', '메이크업', '헤어/바디')
ON CONFLICT DO NOTHING;
