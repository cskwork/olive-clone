# OLV-052 Recommendation

## 선택: Option 1 — 단일 PointService + User 컨트롤러

### 이유
1. V6 트리거(`update_points_balance()`)가 이미 balance 동기화를 담당
2. OLV-051 쿠폰 패턴과 일관성 유지
3. Admin API 불필요 (회원 전용)
4. 단순 SUM 집계로 spendable 계산 가능

---

## 첫 번째 실패 테스트

### `PointServiceTest#earnScheduled_futureAvailableAt_notSpendableUntilAvailable()`

```java
@Test
void earnScheduled_futureAvailableAt_notSpendableUntilAvailable() {
    // Given: 회원 ID, 1000원 적립, 30일 후 사용 가능
    Long memberId = 1L;
    BigDecimal amount = new BigDecimal("1000");
    Long orderId = 100L;
    OffsetDateTime availableAt = now().plusDays(30);
    OffsetDateTime expiresAt = now().plusDays(180);

    // When: 적립
    pointService.earnScheduled(memberId, amount, orderId, availableAt, expiresAt);

    // Then: 현재 시점에서 spendable = 0
    BigDecimal current = pointService.spendableBalance(memberId, now());
    assertThat(current).isEqualByComparingTo("0");

    // But: availableAt 이후에는 spendable = 1000
    BigDecimal after30Days = pointService.spendableBalance(memberId, availableAt);
    assertThat(after30Days).isEqualByComparingTo("1000");
}
```

### 실패 예상
- PointService 클래스가 존재하지 않음 → 컴파일 에러 (RED)

---

## 구현 순서

1. **ErrorCode 추가** (먼저: 의존 방향)
   - `POINT_NOT_FOUND`, `INSUFFICIENT_POINTS`

2. **PointHistory 엔티티**
   - change_type enum: EARN, USE, CANCEL, EXPIRE, ADMIN_ADJUST
   - amount (DECIMAL), available_at, expires_at, order_id

3. **PointHistoryRepository**
   - `findByMemberIdAndAvailableAtBeforeAndExpiresAtAfter()`
   - `findByMemberIdOrderByCreatedAtDesc()` (페이징)
   - `findByOrderId()` (cancel용)

4. **PointService**
   - `earnScheduled()` - PointHistory.insert
   - `spendableBalance()` - SUM where available_at <= asOf < expires_at
   - `use()` - FOR UPDATE 락 + 검증 + USE 행 insert
   - `cancel()` - orderId로 EARN/USE 찾아 CANCEL 행 생성
   - `expire()` - 만료된 EARN 행에 EXPIRE 생성

5. **PointController**
   - `GET /api/me/points` → balance + pending(30일 이내)
   - `GET /api/me/points/history?page=&size=` → 페이징

6. **동시성 테스트**
   - 10스레드 x 20원 사용 → 정확히 5건 성공

---

## 동시성 제어 전략

```
use() 메서드:
1. SELECT * FROM points WHERE member_id=? FOR UPDATE  -- 락 획득
2. spendableBalance(memberId, now()) >= amount 검증
3. PointHistory.insert(USE, amount, order_id)
4. 트랜잭션 커밋 → 트리거가 balance 갱신 → 락 해제
```

`★ Insight ─────────────────────────────────────`
**FOR UPDATE의 중요성**: `points` 행을 잠그면 트리거가 `point_histories`를
갱신하는 동안 다른 트랜잭션이 잔액을 변경할 수 없습니다. 이로써 "잔액 검증 후
사용" 사이의 race condition을 방지합니다. 락 범위를 최소화하기 위해
`SELECT FOR UPDATE`를 use() 진입 시에만 실행합니다.
`─────────────────────────────────────────────────`
