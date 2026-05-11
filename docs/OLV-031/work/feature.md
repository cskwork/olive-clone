# Inventory Service — Admin CRUD + Reserve/Commit/Release

## 개요

재고 도메인의 핵심 서비스를 구현하여 동시성이 안전한 재고 예약/확정/해제 기능과 관리자용 CRUD API를 제공합니다.

## 기능

### 1. 분산 락 기반 재고 예약 (Reserve)

```
POST /api/inventories/reserve
{
  "orderId": 12345,
  "items": [
    {"optionId": 100, "quantity": 2},
    {"optionId": 200, "quantity": 1}
  ],
  "ttlMinutes": 15
}
```

- **동시성 제어**: Redisson RLock으로 option_id별 분산 락 획득
- **Deadlock 방지**: option_id를 오름차순 정렬 후 순차 락 획득
- **All-or-None**: 모든 아이템 재고가 충분할 때만 전체 예약
- **TTL**: 기본 15분, 이후 배치 작업으로 자동 해제

### 2. 결제 승인 시 재고 확정 (Commit)

```
POST /api/inventories/commit
{
  "orderId": 12345
}
```

- `reserved_quantity` 감소 + `total_quantity` 감소
- 예약 상태: `HELD` → `COMMITTED`
- 이미 확정된 주문에 대해 idempotent

### 3. 결제 실패/만료 시 재고 해제 (Release)

```
POST /api/inventories/release
{
  "orderId": 12345,
  "reason": "결제 시간 초과"
}
```

- `reserved_quantity`만 감소 (total은 변하지 않음)
- 예약 상태: `HELD` → `RELEASED`
- commit 후 호출 시 no-op (에러 아님)

### 4. Redis 다운 시 DB 락 Fallback

```
# application.yml
inventory:
  lock:
    fallbackToDb: true
```

- Redisson 연결 실패 시 자동으로 `SELECT ... FOR UPDATE` 사용
- feature flag로 명시적 활성화 필요
- 동일한 동시성 보장 (DB 락)

### 5. 관리자 API

**재고 조회**
```
GET /api/admin/inventories?productId=100
```

**재고 수동 조정**
```
POST /api/admin/inventories/options/{optionId}/adjust
{
  "delta": 10,
  "reason": "반품 입고"
}
```

**만료 예약 일괄 해제**
```
POST /api/admin/inventories/release-expired
```

## 기술적 특징

### 락 전략

| 경로 | 락 방식 | 키/쿼리 | Lease/Wait |
|------|---------|---------|------------|
| Default | Redisson RLock | `lock:inv:{optionId}` | 5s / 2s |
| Fallback | DB PESSIMISTIC_WRITE | `SELECT ... FOR UPDATE` | 트랜잭션 종료까지 |

### Audit Trail

모든 재고 변동은 `inventory_histories` 테이블에 기록됩니다:
- `RESERVE`: 예약 (quantity_delta: 음수)
- `COMMIT`: 확정 (quantity_delta: 음수)
- `RELEASE`: 해제 (quantity_delta: 양수)
- `ADMIN_ADJUST`: 관리자 조정

### 테스트 커버리지

- **AC1**: 50 스레드 동시 예약 → 정확히 30개 성공 (30재고)
- **AC2**: TTL 1초 → 2초 대기 → release → available 복구
- **AC3**: commit 후 release idempotent
- **AC4**: Audit log 2개 기록 (reserve + commit)
- **AC5**: `fallbackToDb=true` 시 DB 락으로 동일한 정합성 보장

## API 예시

### 1. 재고 예약 (성공)

```bash
curl -X POST http://localhost:8080/api/inventories/reserve \
  -H "Content-Type: application/json" \
  -d '{
    "orderId": 1001,
    "items": [{"optionId": 500, "quantity": 2}],
    "ttlMinutes": 15
  }'
```

응답: `204 No Content`

### 2. 재고 예약 (실패 - 재고 부족)

```bash
# 재고가 10개인 상태에서 15개 예약 시도
curl -X POST http://localhost:8080/api/inventories/reserve \
  -H "Content-Type: application/json" \
  -d '{
    "orderId": 1002,
    "items": [{"optionId": 500, "quantity": 15}],
    "ttlMinutes": 15
  }'
```

응답: `409 Conflict`
```json
{
  "success": false,
  "error": {
    "code": "INSUFFICIENT_INVENTORY",
    "message": "Insufficient inventory for option: 500, available: 10, requested: 15"
  }
}
```

### 3. 관리자 재고 조회

```bash
curl -X GET http://localhost:8080/api/admin/inventories?productId=100
```

응답: `200 OK`
```json
{
  "success": true,
  "data": [
    {
      "id": 1,
      "productOptionId": 500,
      "totalQuantity": 100,
      "reservedQuantity": 5,
      "availableQuantity": 95,
      "updatedAt": "2026-05-11T12:34:56Z"
    }
  ]
}
```

## 모니터링

### 로그

```
INFO  c.o.c.c.RedissonConfig - Redisson distributed lock client initialized (address=localhost:6379, lockWaitTime=2s, lockLeaseTime=5s, fallbackToDb=false)
INFO  c.o.c.i.InventoryService - Released 5 expired reservations
```

### 헬스체크

```
GET /actuator/health
{
  "components": {
    "redis": {"status": "UP"},
    "db": {"status": "UP"}
  }
}
```

## 관련 티켓

- **OLV-030**: V4 스키마 (inventories, inventory_histories, inventory_reservations)
- **OLV-003**: Redis + Testcontainers baseline
- **OLV-120**: 만료 예약 배치 스케줄링
