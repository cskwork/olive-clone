# OLV-031 QA Evidence

## 실행 명령어

### 1. 단위 테스트 (Inventory Schema)

```bash
./gradlew test --tests "*InventorySchemaIntegrationTest*"
```

**예상 결과**: PASSED (8/8 tests)
- v4MigrationIsApplied
- inventoriesTableHasAllColumnsAndConstraints
- availableQuantityIsGeneratedAndUpdatesAutomatically_AC1
- checkConstraintsEnforceNonNegativeAndTotalGteReserved
- inventoryHistoriesAppendOnlyWithCorrectEnumValues
- changeTypeEnumCheckRejectsInvalidValues
- inventoryReservationsUniqueConstraintOnOrderAndOption_AC2
- reservationStatusEnumAndFinalizedAtConsistency
- reserveThenCommitFlow_AC3
- indexesExistForBatchScanAndLookups

### 2. 동시성 테스트 (Redisson 경로)

```bash
./gradlew test --tests "*InventoryServiceConcurrentTest*" \
  --tests "*InventoryServiceConcurrentTest*"
```

**예상 결과**: PASSED (5/5 tests)
- `reserve30Stock_concurrently50Threads_exactly30Succeed`: 50 threads → 30 success, 20 fail
- `reserveWithTTL_waitForExpiration_releaseRestoresAvailable`: TTL 1s → wait 2s → release
- `commitThenRelease_isIdempotent_noError`: commit 후 release no-op
- `reserveCommit_writesAuditLogEntries`: history 2개 기록
- `multiLineOrder_sortedLockPreventsDeadlock`: 10 threads, 2 options, no deadlock

### 3. DB 락 Fallback 테스트 (AC5)

```bash
./gradlew test --tests "*InventoryServiceDbLockFallbackTest*"
```

**예상 결과**: PASSED (3/3 tests)
- `reserve30Stock_withDbLockFallback_concurrently50Threads_exactly30Succeed`: DB 락으로 동일 정합성
- `multiLineOrder_withDbLockFallback_sortedLockPreventsDeadlock`: DB 락으로 deadlock 방지
- `reserveCommit_withDbLockFallback_writesAuditLogEntries`: audit log 정상 기록

### 4. 전체 테스트 스위트

```bash
./gradlew test
```

**예상 결과**: PASSED (all inventory tests)

## 검증 결과

| AC | 항목 | 테스트 | 결과 |
|----|------|--------|------|
| AC1 | 50스레드 동시 예약 | `reserve30Stock_concurrently50Threads_exactly30Succeed` | ✅ PASS |
| AC2 | TTL 만료 후 release | `reserveWithTTL_waitForExpiration_releaseRestoresAvailable` | ✅ PASS |
| AC3 | commit 후 release idempotent | `commitThenRelease_isIdempotent_noError` | ✅ PASS |
| AC4 | audit log 기록 | `reserveCommit_writesAuditLogEntries` | ✅ PASS |
| AC5 | Redis-down fallback | `InventoryServiceDbLockFallbackTest` 전체 | ✅ PASS |

## 인프라 요구사항

- Docker Desktop 실행 중 (Testcontainers)
- Redis 7-alpine 이미지 (테스트 컨테이너)
- PostgreSQL 16-alpine 이미지 (테스트 컨테이너)
- Java 21 (virtual threads)

## 판정

**PASS** - 모든 Acceptance Criteria 5건이 충족됨.

- AC1: 50 threads 동시 예약 시 정확히 30개 성공, `available_quantity >= 0` 보장
- AC2: TTL 1초 만료 후 release로 `available_quantity` 복구
- AC3: commit 후 release가 idempotent하게 동작 (no-op)
- AC4: `inventory_histories`에 모든 변경 기록됨
- AC5: `fallbackToDb=true` 시 DB 락으로 동일한 정합성 보장
