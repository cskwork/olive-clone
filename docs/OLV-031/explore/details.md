# OLV-031 Explore Details

## Wiki References (핵심 인용)

### 1. Inventory Domain (`llm-wiki/30-inventory-domain.md`)
- **줄 7-14**: Per-option 재고 구조. `inventories` 테이블은 `product_option_id` 기준. `available_quantity = total - reserved`는 GENERATED 컬럼으로 서비스 버그 방지.
- **줄 15-19**: Reserve-then-commit 필수. Order create → reserve (TTL 15분), Payment APPROVED → commit (total 감소), FAILED/TTL expired → release (reserved 감소).
- **줄 20-24**: Concurrency 전략. **Default**: Redisson `RLock` (key=`lock:inv:{product_option_id}`, lease 5s, wait 2s). **Fallback**: Redis 다운 시 `SELECT ... FOR UPDATE` + `@Transactional`.
- **줄 25-28**: Audit 강제. 모든 reserve/commit/release는 `inventory_histories`에 기록. (reason, order_id, delta, ts). 배치는 매 5분마다 TTL 만료 처리 (PRD §17.2).

### 2. Infra Baseline (`llm-wiki/03-infra-baseline.md`)
- **줄 14-19**: Redis 빈은 자동설정 위임 (`StringRedisTemplate extends RedisTemplate<String,String>`). **새로 빈을 정의하면 `BeanDefinitionOverrideException`**.
- **줄 50-53**: **OLV-030의 Redisson `RLock`은 본 `RedisConnectionFactory` 위에 별도 빈을 쌓는다** — Redis 자체는 baseline이 보장. 본 티켓이 그 "쌓아 올리기" 담당.
- **줄 38-40**: Testcontainers Redis = `GenericContainer("redis:7-alpine")`. Redisson은 Testcontainers Redis와 연동 가능.

### 3. Common Conventions (`llm-wiki/01-common-conventions.md`)
- **줄 24**: 도메인 서비스는 `BusinessException(ErrorCode, String detail)`만 던진다. `INSUFFICIENT_INVENTORY`는 이미 정의됨 (ErrorCode 줄 9).
- **줄 7-11**: 응답 봉투 `ApiResponse<T>`. 성공: `{"success": true, "data": <T>}`, 실패: `{"success": false, "error": ErrorBody}`.
- **줄 34**: audit 카테고리: `INVENTORY_RESERVED`, `INVENTORY_RELEASED`, `INVENTORY_COMMITTED`, `ADMIN_ADJUST`.

### 4. PRD §10.2 (Concurrency)
- "Multi-line orders는 **sorted option_id 순서로 락 획득**" — deadlock 방지 (티켓 §Hints 줄 37).
- "Lock 획득 → inventory 검증 → mutation → lock 해제" 순서 (티켓 §Hints 줄 38).
- "Lock 해제는 **역순 finally**" (티켓 §Hints 줄 39).

## 기존 코드 분석

### V4__inventory.sql (OLV-030)
- 3개 테이블: `inventories`, `inventory_histories`, `inventory_reservations`
- `inventory_reservations.status`: `HELD` | `COMMITTED` | `RELEASED`
- `inventory_histories.change_type`: `STOCK_IN` | `STOCK_OUT` | `RESERVE` | `COMMIT` | `RELEASE` | `ADMIN_ADJUST`
- AC3 flow (V4 줄 271-353): reserve → commit 흐름을 native query로 검증. 서비스는 이를 메서드로 구현.

### BrandAdminService 패턴
- `@Service` + 생성자 주입
- `@Transactional` on write methods
- `BusinessException` throw with `ErrorCode`
- Repository → Entity → DTO 변환

## 위험 분석

### R1: Redisson 버전 충돌
- Redisson 3.x는 `RedisConnectionFactory`를 받아 `RedissonClient` 빈 생성 가능.
- Spring Boot 3.x + Lettuce (starter-data-redis 기본)와 호환 확인 필요.
- 완화: `org.redisson:redisson:3.37.0` (최신 stable) 사용. Spring Boot 3.3 호환 확인됨.

### R2: Deadlock on multi-line orders
- option_id 정렬 없이 락 획득 시 교착 상태 가능.
- 완화: **option_id 리스트를 반드시 정렬 후 순차 락 획득** (PRD §10.2 명시).

### R3: Testcontainers Redis + Redisson connection
- `GenericContainer("redis:7-alpine")`의 mapped port를 Redisson이 바라보도록 설정.
- 완화: `@DynamicPropertySource`로 `spring.data.redis.host/port`를 컨테이너 포트로 오버라이드.

### R4: Concurrent test flakiness
- 50 virtual threads + actual network I/O → timing-dependent.
- 완화: **AssertJ의 `await().atMost(10, TimeUnit.SECONDS).untilAsserted()`**로 레이스 컨디션 방지. 실제 50스레드가 모두 종료될 때까지 대기 후 검증.

### R5: Redis fallback → DB path 이중 구현
- Redisson 실패 시 `SELECT ... FOR UPDATE`로 폴백하는 로직이 추가 복잡도.
- 완화: Feature flag `inventory.lock.fallbackToDb`로 분기. 테스트는 두 경로 모두 검증 (AC5).

## 구현 계획 (Implementation Preview)

### 1. 의존성 추가 (build.gradle.kts)
```kotlin
extra["redissonVersion"] = "3.37.0"
dependencies {
    implementation("org.redisson:redisson:${property("redissonVersion")}")
}
```

### 2. RedissonConfig (common/config)
- `RedissonClient` 빈 정의. `RedisConnectionFactory` → `SingleServerConfig` → `RedissonClient`.
- **init 로그**: "Redisson distributed lock client initialized".

### 3. Inventory Domain (inventory package)
- **Entity**: `Inventory`, `InventoryHistory`, `InventoryReservation` (JPA)
- **Repository**: `InventoryRepository`, `InventoryHistoryRepository`, `InventoryReservationRepository` (Spring Data JPA)
- **Service**: `InventoryService` (핵심)

### 4. InventoryService 메서드 시그니처
```java
// reserve: atomic across all items. ALL-or-NONE.
void reserve(Long orderId, List<ReserveItem> items, Duration ttl);

// commit: payment approved
void commit(Long orderId);

// release: payment failed or TTL expired
void release(Long orderId, String reason);

// adjust: admin manual stock movement
void adjust(Long optionId, int delta, String reason, Long adminId);
```

### 5. Locking 구현 (핵심 로직)
```java
// Default path: Redisson
List<RLock> locks = items.stream()
    .map(ReserveItem::optionId)
    .sorted()  // DEADLOCK 방지
    .map(id -> redissonClient.getLock("lock:inv:" + id))
    .toList();

try {
    // 모든 락 획득 (각각 wait 2s, lease 5s)
    for (RLock lock : locks) {
        if (!lock.tryLock(2, 5, TimeUnit.SECONDS)) {
            throw new BusinessException(ErrorCode.LOCK_ACQUISITION_FAILED, ...);
        }
    }
    // 락 내부에서 검증 + mutation
    doReserve(items);
} finally {
    // 역순 해제
    Collections.reverse(locks);
    locks.forEach(RLock::unlock);
}
```

### 6. Fallback path (DB lock)
- `inventory.lock.fallbackToDb=true` 시 `@Transactional` + `SELECT ... FOR UPDATE`.
- `InventoryRepository.findByIdWithLock(optionId)` (custom query).

### 7. Admin API
- `GET /api/admin/inventories?productId=` → list per-option state
- `POST /api/admin/inventories/{optionId}/adjust` → `{delta, reason}`
- `@PreAuthorize("hasRole('INVENTORY_ADMIN')")` (또는 `PRODUCT_ADMIN`)

### 8. First Failing Test (TDD seed)
```java
@Test
void reserve30Stock_concurrently50Threads_exactly30Succeed() {
    // Given: optionId=1, total=30
    // When: 50 virtual threads each reserve(1)
    // Then: exactly 30 success, 20 INSUFFICIENT_INVENTORY
    //       available_quantity never < 0
}
```

## AC 검증 계획

| AC | 검증 방법 |
|---|---|
| AC1: Concurrent test (30 stock, 50 threads) | `CompletableFuture.allOf()` + 50 `Thread.ofVirtual().start()` → AssertJ await로 정합성 검증 |
| AC2: TTL expire → release → restore | reserve(TTL=1s) → Thread.sleep(2s) → `releaseExpired()` → available == original |
| AC3: Commit then release idempotent | commit(orderId) → release(orderId) → second release is no-op (history only) |
| AC4: Audit log entries | `inventory_histories` count = 2 per reserve+commit, reason/order_id/delta 모두 존재 |
| AC5: Redis-down fallback | Docker stop Redis → feature flag ON → repeat AC1 → same invariant |

## 의존성 후보 확인

1. **Redisson 3.37.0**: Spring Boot 3.3 + Lettuce 호환. Testcontainers Redis 연동 확인됨.
2. **JPA**: V4로 테이블 존재. Entity는 본 티켓에서 생성.
3. **ErrorCode**: `INSUFFICIENT_INVENTORY` 이미 정의됨 (common/error/ErrorCode.java 줄 9).
4. **Audit**: `AuditLogger` 빈 존재 (common/audit). 카테고리 `INVENTORY_RESERVED` 등 사용.
