# OLV-020 Explore Phase Details

## Domain Context

### Product Domain Invariants (from llm-wiki/20-product-domain.md)

1. **Separate tables for products and options** — a single product can have many options (color, volume, set composition). Inventory is per-option, not per-product.
2. **Product status enum**: DRAFT, ON_SALE, SOLD_OUT, STOPPED, HIDDEN
3. **Money fields**: DECIMAL(12,2) in database, BigDecimal in Java code
4. **Images**: only URL stored in database; actual images in S3-compatible storage
5. **Categories**: hierarchical structure with self-referencing FK

### Index Requirements

1. `products(status, brand_id)` — composite index for category-list queries
2. `products(name text_pattern_ops)` — special operator class for LIKE 'prefix%' searches
3. `product_options(product_id)` — FK index for option lookups
4. `product_images(product_id, sort_order)` — composite for ordered image lists

## Risks & Mitigations

### R1: Brand name case sensitivity
- **Risk**: VARCHAR UNIQUE is case-sensitive; "TheSaem" vs "thesecret" would be distinct
- **Mitigation**: MVP acceptable; can migrate to CITEXT later if needed

### R2: Category mapping duplicates
- **Risk**: Application might try to map same product to same category twice
- **Mitigation**: composite PRIMARY KEY (product_id, category_id) prevents duplicates at database level

### R3: Partial unique index on categories
- **Risk**: `uniq_categories_name_per_parent` only protects top-level (parent_id IS NULL)
- **Mitigation**: Documented; child category uniqueness enforced at application level

## File Patterns from V2__member.sql

1. Use `set_updated_at()` trigger (defined in V2) for all tables with updated_at
2. FK constraints: `REFERENCES table(id) ON DELETE CASCADE` or `SET NULL`
3. CHECK constraints for enum values: `CHECK (status IN ('VAL1', 'VAL2', ...))`
4. COMMENT ON TABLE/COLUMN for documentation
5. Index naming: `idx_tablename_col1_col2`
