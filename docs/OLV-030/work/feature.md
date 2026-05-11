# OLV-030 Inventory Schema Feature

## Overview

OLV-030는 재고 도메인의 핵심 테이블 3개를 생성하고, Repository 테스트로 스키마를 검증하는 티켓입니다. 이 스키마는 후속 Inventory Service(OLV-031)와 Order(OLV-040) 티켓의 기반이 됩니다.

## Files Changed

| File | Description |
|------|-------------|
| `src/main/resources/db/migration/V4__inventory.sql` | 재고 도메인 스키마 (137 lines) |
| `src/test/java/.../inventory/InventorySchemaIntegrationTest.java` | 스키마 검증 테스트 (11 tests) |

## Schema Details

### 1. inventories

재고 본 테이블로, `product_option_id`마다 한 행이 존재합니다.

```sql
CREATE TABLE inventories (
    id                 BIGSERIAL PRIMARY KEY,
    product_option_id  BIGINT       NOT NULL UNIQUE REFERENCES product_options(id) ON DELETE RESTRICT,
    total_quantity     INTEGER      NOT NULL DEFAULT 0,
    reserved_quantity  INTEGER      NOT NULL DEFAULT 0,
    available_quantity INTEGER      GENERATED ALWAYS AS (total_quantity - reserved_quantity) STORED,
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT inventories_total_non_negative CHECK (total_quantity >= 0),
    CONSTRAINT inventories_reserved_non_negative CHECK (reserved_quantity >= 0),
    CONSTRAINT inventories_total_gte_reserved CHECK (total_quantity >= reserved_quantity)
);
```

**특징:**
- `available_quantity`는 GENERATED 컬럼으로, `total_quantity - reserved_quantity`를 자동 계산합니다. 서비스 코드가 실수로 available 값을 직접 수정하는 것을 방지합니다 (AC1).
- `ON DELETE RESTRICT`로 옵션 삭제 시 재고가 남아있으면 삭제를 거부합니다.
- CHECK 제약으로 음수 값 방지 및 `total >= reserved` 보장.

### 2. inventory_histories

모든 재고 변경 이력을 기록하는 append-only 테이블입니다.

```sql
CREATE TABLE inventory_histories (
    id               BIGSERIAL PRIMARY KEY,
    product_option_id BIGINT       NOT NULL REFERENCES product_options(id) ON DELETE RESTRICT,
    change_type      VARCHAR(20)   NOT NULL,
    quantity_delta   INTEGER       NOT NULL,
    reason           VARCHAR(255),
    order_id         BIGINT,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by       BIGINT,
    CONSTRAINT inventory_histories_change_type_check CHECK (
        change_type IN ('STOCK_IN', 'STOCK_OUT', 'RESERVE', 'COMMIT', 'RELEASE', 'ADMIN_ADJUST')
    )
);
```

**특징:**
- `change_type` enum: STOCK_IN, STOCK_OUT, RESERVE, COMMIT, RELEASE, ADMIN_ADJUST
- 시계열 조회용 인덱스: `idx_inventory_histories_created_at DESC`

### 3. inventory_reservations

주문별 재고 예약을 관리하는 테이블입니다. 배치 작업이 만료된 예약을 찾을 수 있도록 인덱스가 설계되었습니다.

```sql
CREATE TABLE inventory_reservations (
    id                BIGSERIAL PRIMARY KEY,
    order_id          BIGINT       NOT NULL,
    product_option_id BIGINT       NOT NULL REFERENCES product_options(id) ON DELETE RESTRICT,
    quantity          INTEGER      NOT NULL CHECK (quantity > 0),
    status            VARCHAR(20)  NOT NULL DEFAULT 'HELD',
    expires_at        TIMESTAMPTZ  NOT NULL,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    finalized_at      TIMESTAMPTZ,
    CONSTRAINT inventory_reservations_status_check CHECK (
        status IN ('HELD', 'COMMITTED', 'RELEASED')
    ),
    CONSTRAINT inventory_reservations_status_finalized_consistency CHECK (
        (status = 'HELD' AND finalized_at IS NULL) OR
        (status IN ('COMMITTED', 'RELEASED') AND finalized_at IS NOT NULL)
    )
);
```

**특징:**
- `uniq_inventory_reservations_order_option` UNIQUE 인덱스로 (order_id, product_option_id) 중복 방지 (AC2).
- `idx_inventory_reservations_status_expires`로 배치 스캔 최적화: `WHERE status = 'HELD' AND expires_at < now()`
- status/finalized_at consistency CHECK 제약으로 HELD 상태에서 finalized_at 설정 방지.

## Acceptance Criteria

### AC1: GENERATED 컬럼 자동 갱신

```sql
-- 초기: total=100, reserved=0 → available=100
INSERT INTO inventories (product_option_id, total_quantity, reserved_quantity)
VALUES (1, 100, 0);

-- reserved 변경
UPDATE inventories SET reserved_quantity = 30 WHERE id = 1;
-- available 자동으로 70으로 변경

-- total 변경
UPDATE inventories SET total_quantity = 70 WHERE id = 1;
-- available 자동으로 40으로 변경
```

테스트: `InventorySchemaIntegrationTest#availableQuantityIsGeneratedAndUpdatesAutomatically_AC1`

### AC2: UNIQUE 제약으로 중복 예약 방지

```sql
INSERT INTO inventory_reservations (order_id, product_option_id, quantity, status, expires_at)
VALUES (10001, 1, 1, 'HELD', now() + INTERVAL '15 minutes');

-- 같은 (order_id, product_option_id)로 두 번째 삽입 시도 → UNIQUE 위반
INSERT INTO inventory_reservations (order_id, product_option_id, quantity, status, expires_at)
VALUES (10001, 1, 1, 'HELD', now() + INTERVAL '15 minutes');
-- ERROR: duplicate key value violates unique constraint "uniq_inventory_reservations_order_option"
```

테스트: `InventorySchemaIntegrationTest#inventoryReservationsUniqueConstraintOnOrderAndOption_AC2`

### AC3: Reserve-then-commit flow

```sql
-- 초기 상태: total=100, reserved=0
INSERT INTO inventories (product_option_id, total_quantity, reserved_quantity)
VALUES (1, 100, 0);

-- 1. Reserve 30 → reserved=30, available=70
UPDATE inventories SET reserved_quantity = 30 WHERE product_option_id = 1;
INSERT INTO inventory_histories (product_option_id, change_type, quantity_delta, reason, order_id)
VALUES (1, 'RESERVE', -30, '주문 예약', 10004);

-- 2. Commit 30 → total=70, reserved=0, available=70
UPDATE inventories
SET total_quantity = total_quantity - 30,
    reserved_quantity = reserved_quantity - 30
WHERE product_option_id = 1;
INSERT INTO inventory_histories (product_option_id, change_type, quantity_delta, reason, order_id)
VALUES (1, 'COMMIT', -30, '결제 승인', 10004);
```

테스트: `InventorySchemaIntegrationTest#reserveThenCommitFlow_AC3`

## Indexes

| Index | Purpose |
|-------|---------|
| `idx_inventories_product_option_id` | option → inventory 조회 |
| `idx_inventory_histories_product_option_id` | option-specific history |
| `idx_inventory_histories_created_at` | 시계열 audit 조회 |
| `uniq_inventory_reservations_order_option` | 중복 예약 방지 (AC2) |
| `idx_inventory_reservations_status_expires` | 배치 만료 스캔 (PRD §17.2) |
| `idx_inventory_reservations_product_option_id` | option-specific reservations |

## Next Steps

- OLV-031 (Inventory Service): Reserve/Commit/Release 메서드 구현, Redis 분산 락 적용
- OLV-040 (Order): 주문 생성 시 Inventory.reserve() 호출, 결제 승인 시 Inventory.commit() 호출
