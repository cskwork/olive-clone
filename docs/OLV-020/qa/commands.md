# OLV-020 QA Commands

## Prerequisites

1. Java 21 installed and JAVA_HOME set
2. Docker daemon running
3. PostgreSQL container available: `docker compose up -d postgres`

## Acceptance Criteria Verification

### AC1: Flyway V3 Applied

```bash
./gradlew flywayInfo
```

Expected output includes:
```
| Version | Description      | Type | Installed On | State
---------------------------------------------------------
| 1       | init baseline    | SQL  | ...          | Success
| 2       | member           | SQL  | ...          | Success
| 3       | product          | SQL  | ...          | Success  ← AC1
```

### AC2: Repository Test

```bash
./gradlew test --tests ProductSchemaIntegrationTest
```

Expected output:
```
ProductSchemaIntegrationTest > v3MigrationIsApplied PASSED
ProductSchemaIntegrationTest > seedBrandExists PASSED
...
BUILD SUCCESSFUL
11 tests completed, 0 failed
```

### AC3: EXPLAIN Uses text_pattern_ops Index

```bash
# Connect to running PostgreSQL
docker exec -it commerce-postgres psql -U commerce -d commerce

# Run EXPLAIN
EXPLAIN SELECT * FROM products WHERE status='ON_SALE' AND name LIKE '선크림%';
```

Expected output includes:
```
Index Scan using idx_products_name_pattern on products  ← AC3
  Index Cond: ((name)::text ~~ '선크림%'::text)
  Filter: (status = 'ON_SALE'::varchar)
```

## Full Test Suite

```bash
# Run all tests (should pass without regression)
./gradlew test
```

## Manual Smoke Test

```sql
-- Verify seed data
SELECT name, slug FROM brands;
SELECT name, slug, depth FROM categories ORDER BY sort_order;
SELECT name, base_price, sale_price FROM products WHERE name LIKE '%선크림%';
SELECT option_name, option_price FROM product_options WHERE product_id = (SELECT id FROM products WHERE name LIKE '%선크림%');

-- Verify indexes exist
SELECT indexname FROM pg_indexes WHERE tablename IN ('products', 'product_options', 'product_images');

-- Verify constraints
SELECT conname FROM pg_constraint WHERE conrelid = 'products'::regclass AND contype = 'c';
```

## Cleanup (if needed)

```bash
# Stop PostgreSQL
docker compose down

# Clean build artifacts (optional)
./gradlew clean
```

## Environment-Specific Notes

### Port 5432 Already in Use

If port 5432 is occupied:
```bash
# Use alt-port override
docker compose -f docker-compose.yml -f docs/OLV-002/qa/compose-override-alt-port.yml up -d postgres
export FLYWAY_URL=jdbc:postgresql://localhost:55432/commerce
export SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:55432/commerce
./gradlew flywayInfo
```

### Testcontainers Docker API Version

If Testcontainers fails with "client version too old":
- Ensure Docker Desktop is updated (API 1.44+)
- Testcontainers BOM should be 1.21.4+ (already in build.gradle.kts)
