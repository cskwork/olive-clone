# OLV-060 QA Evidence

## AC1: V7 Applied

**Command:**
```bash
./gradlew test --tests OrderSchemaIntegrationTest.v7MigrationIsApplied
```

**Result:** ✅ PASS

**Output:**
```
OrderSchemaIntegrationTest > v7MigrationIsApplied PASSED
```

**Verification:** Flyway schema history contains version 7 with success=TRUE.

---

## AC2: order_no Generator (50 concurrent inserts)

**Command:**
```bash
./gradlew test --tests OrderSchemaIntegrationTest.orderNoGenerator_NoCollisionsUnderConcurrentInserts
```

**Result:** ✅ PASS

**Output:**
```
OrderSchemaIntegrationTest > orderNoGenerator_NoCollisionsUnderConcurrentInserts PASSED
```

**Verification:**
- 50 orders inserted successfully
- All order_no values are unique
- Format: ORD<yyyyMMdd><6-digit-seq> (e.g., ORD20260511000001)

---

## AC3: Indexes Exist

**Commands:**
```bash
./gradlew test --tests OrderSchemaIntegrationTest.ac3_Index_MemberIdCreatedAtDesc_Exists
./gradlew test --tests OrderSchemaIntegrationTest.ac3_Index_StatusCreatedAt_Exists
./gradlew test --tests OrderSchemaIntegrationTest.ac3_Index_OrderNo_Unique_Exists
```

**Result:** ✅ PASS (3/3)

**Indexes Verified:**
1. `idx_orders_member_created` on (member_id, created_at DESC) - for list view
2. `idx_orders_status_created` on (status, created_at) - for payment-pending expiry batch
3. `order_no` UNIQUE constraint - already verified as part of table definition

---

## Full Test Suite

**Command:**
```bash
./gradlew test --tests "*SchemaIntegrationTest"
```

**Result:** ✅ PASS

**Summary:** All SchemaIntegrationTest tests pass (V1 through V7 migrations verified).
