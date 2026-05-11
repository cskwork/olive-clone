# OLV-062 Explore: Plan Candidates

## Option 1: 단일 `OrderService.cancel()` 메서드에 취소 로직 전체 구현

### 구조
```java
@Transactional
public void cancelUserOrder(Long memberId, String orderNo, String reason) {
    // 1. 주문 조회 및 소유권 검증
    // 2. 취소 가능 상태 검증
    // 3. PG 취소 (PAID 상태인 경우)
    // 4. 재고 해제
    // 5. 쿠폰 복구
    // 6. 포인트 복구
    // 7. 상태 전이
    // 8. 이력 기록
    // 9. 이벤트 발행
}
```

### 장점
- 트랜잭션 경계가 명확
- 코드 파악이 쉬움
- 롤백 시나리오가 단순

### 단점
- 메서드가 길어짐 (예상 80~120줄)
- PG 취소 실패 시 부분 롤백 복잡
- 테스트가 복잡해질 수 있음

### 복잡도: 낮음

---

## Option 2: 취소 단계를 별도 private 메서드로 분리

### 구조
```java
@Transactional
public void cancelUserOrder(Long memberId, String orderNo, String reason) {
    Order order = findAndValidateOwnership(memberId, orderNo);
    validateCancellable(order);

    CancelResult result = executeCancel(order, reason, CancelKind.USER);

    updateOrderStatus(order, result);
    recordHistory(order, result);
    publishEvent(order, result);
}

private CancelResult executeCancel(Order order, String reason, CancelKind kind) {
    CancelResult result = new CancelResult();

    // PG 취소
    if (order.getStatus() == PAID) {
        result.setPgCanceled(paymentService.cancel(...));
    }

    // 재고 해제
    inventoryService.release(order.getId(), reason);

    // 쿠폰 복구
    if (order.getUsedMemberCouponId() != null) {
        couponService.restore(order.getUsedMemberCouponId(), order.getId());
    }

    // 포인트 복구
    pointService.cancel(order.getMemberId(), order.getId());

    return result;
}
```

### 장점
- 각 단계가 독립 메서드라 테스트가 쉬움
- 코드 가독성 향상
- 관리자/사용자 경로에서 로직 재사용 가능

### 단점
- 메서드 간 상태 공유가 필요 (CancelResult DTO)
- PG 취소 실패 시 복구 로직이 분산될 수 있음

### 복잡도: 중간

---

## Option 3: CancelCommand 패턴과 책임 체인 적용

### 구조
```java
public interface CancelStep {
    void execute(CancelContext context);
    int getOrder();
}

@Component
public class PgCancelStep implements CancelStep { order = 1; }

@Component
public class InventoryReleaseStep implements CancelStep { order = 2; }

@Component
public class CouponRestoreStep implements CancelStep { order = 3; }

@Component
public class PointRestoreStep implements CancelStep { order = 4; }

@Service
public class OrderCancelService {
    private final List<CancelStep> steps;

    @Transactional
    public void cancel(Long memberId, String orderNo, String reason) {
        CancelContext context = new CancelContext(order, reason, CancelKind.USER);
        steps.forEach(step -> step.execute(context));
    }
}
```

### 장점
- 단일 책임 원칙 준수
- 각 단계가 독립적으로 테스트 가능
- 새로운 취소 단계 추가가 쉬움
- 순서 제어가 명확

### 단점
- 클래스 수 증가 (5개 이상의 새 클래스)
- 이 티켓 범위에서는 과잉 설계일 수 있음
- 트랜잭션 경계가 더 복잡해질 수 있음

### 복잡도: 높음

---

## Option 4: 상태별 전략 패턴 적용

### 구조
```java
public interface CancelStrategy {
    boolean canCancel(Order.OrderStatus status);
    void cancel(Order order, String reason);
}

@Component
public class PaymentPendingCancelStrategy implements CancelStrategy {
    public boolean canCancel(Order.OrderStatus status) {
        return status == PAYMENT_PENDING;
    }
    public void cancel(Order order, String reason) {
        // 재고만 해제, PG 호출 없음
    }
}

@Component
public class PaidCancelStrategy implements CancelStrategy {
    public boolean canCancel(Order.OrderStatus status) {
        return status == PAID || status == PREPARING;
    }
    public void cancel(Order order, String reason) {
        // 전체 취소 로직
    }
}
```

### 장점
- 상태별 취소 로직이 명확히 분리
- 새로운 상태에 대한 취소 로직 추가가 쉬움
- OCP 준수

### 단점
- 클래스 수 증가
- 전략 간 공통 로직 중복 가능
- 이 티켓 범위에서는 과잉 설계

### 복잡도: 중간-높음

---

## 비교 요약

| Option | 장점 | 단점 | 추천 상황 |
|--------|------|------|----------|
| 1. 단일 메서드 | 간단, 트랜잭션 명확 | 긴 메서드, 테스트 복잡 | 프로토타입 |
| 2. 메서드 분리 | 가독성, 테스트 용이 | DTO 추가 필요 | **✅ MVP (본 티켓)** |
| 3. 책임 체인 | 확장성, SRB | 클래스 폭발, 복잡 | 대규모 시스템 |
| 4. 전략 패턴 | 상태별 분리 | 중복 가능, 복잡 | 상태 로직이 복잡한 경우 |
