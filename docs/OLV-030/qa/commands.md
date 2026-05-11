# OLV-030 QA Evidence

## Summary

All acceptance criteria satisfied. V4__inventory.sql successfully applied, all 10 tests PASSED.

## Commands Run

```bash
cd /Users/danny/Documents/PARA/Resource/olive-clone
./gradlew test --tests InventorySchemaIntegrationTest
```

## Exit Code

0 (BUILD SUCCESSFUL)

## Key Output

```
> Task :test

Deprecated Gradle features were used in this build, making it incompatible with Gradle 9.0.

BUILD SUCCESSFUL in 7s
5 actionable tasks: 4 executed, 1 up-to-date
```

## Flyway Migration Log (from test output)

```
2026-05-11 15:40:59.421 INFO  [traceId=] org.flywaydb.core.FlywayExecutor - Database: jdbc:postgresql://localhost:57461/commerce (PostgreSQL 16.12)
2026-05-11 15:40:59.444 INFO  [traceId=] o.f.c.i.s.JdbcTableSchemaHistory - Schema history table "public"."flyway_schema_history" does not exist yet
2026-05-11 15:40:59.445 INFO  [traceId=] o.f.core.internal.command.DbValidate - Successfully validated 4 migrations (execution time 00:00.009s)
2026-05-11 15:40:59.457 INFO  [traceId=] o.f.c.i.s.JdbcTableSchemaHistory - Creating Schema History table "public"."flyway_schema_history" ...
2026-05-11 15:40:59.491 INFO  [traceId=] o.f.core.internal.command.DbMigrate - Current version of schema "public": << Empty Schema >>
2026-05-11 15:40:59.497 INFO  [traceId=] o.f.core.internal.command.DbMigrate - Migrating schema "public" to version "1 - init baseline"
2026-05-11 15:40:59.523 INFO  [traceId=] o.f.core.internal.command.DbMigrate - Migrating schema "public" to version "2 - member"
2026-05-11 15:40:59.561 INFO  [traceId=] o.f.core.internal.command.DbMigrate - Migrating schema "public" to version "3 - product"
2026-05-11 15:40:59.596 INFO  [traceId=] o.f.core.internal.command.DbMigrate - Migrating schema "public" to version "4 - inventory"
2026-05-11 15:40:59.620 INFO  [traceId=] o.f.core.internal.command.DbMigrate - Successfully applied 4 migrations to schema "public", now at version v4 (execution time 00:00.078s)
```

## Test Results

| Test Name | Status |
|-----------|--------|
| v4MigrationIsApplied | ✅ PASSED |
| inventoriesTableHasAllColumnsAndConstraints | ✅ PASSED |
| availableQuantityIsGeneratedAndUpdatesAutomatically_AC1 | ✅ PASSED |
| checkConstraintsEnforceNonNegativeAndTotalGteReserved | ✅ PASSED |
| inventoryHistoriesAppendOnlyWithCorrectEnumValues | ✅ PASSED |
| changeTypeEnumCheckRejectsInvalidValues | ✅ PASSED |
| inventoryReservationsUniqueConstraintOnOrderAndOption_AC2 | ✅ PASSED |
| reservationStatusEnumAndFinalizedAtConsistency | ✅ PASSED |
| reserveThenCommitFlow_AC3 | ✅ PASSED |
| indexesExistForBatchScanAndLookups | ✅ PASSED |

## AC Verification

### AC1: Generated column updates automatically
Test: `availableQuantityIsGeneratedAndUpdatesAutomatically_AC1`
- Initial: total=100, reserved=0 → available=100 ✅
- After reserved=30: available=70 ✅
- After total=70: available=40 ✅

### AC2: UNIQUE constraint prevents duplicate reservations
Test: `inventoryReservationsUniqueConstraintOnOrderAndOption_AC2`
- First insert: SUCCESS ✅
- Second insert (same order_id, product_option_id): `ERROR: duplicate key value violates unique constraint "uniq_inventory_reservations_order_option"` ✅

### AC3: Reserve-then-commit flow
Test: `reserveThenCommitFlow_AC3`
- Initial: total=100, reserved=0, available=100 ✅
- After reserve 30: total=100, reserved=30, available=70 ✅
- After commit 30: total=70, reserved=0, available=70 ✅

## Constraint Verification

Expected SQL errors observed in test output:
- `inventories_total_non_negative`: `total_quantity >= 0` ✅
- `inventories_reserved_non_negative`: `reserved_quantity >= 0` ✅
- `inventories_total_gte_reserved`: `total_quantity >= reserved_quantity` ✅
- `inventory_histories_change_type_check`: Invalid `change_type` rejected ✅
- `inventory_reservations_status_finalized_consistency`: HELD with finalized_at rejected ✅

## 판정

**✅ PASSED** - All acceptance criteria satisfied, all 10 tests passed, V4 migration successfully applied.
