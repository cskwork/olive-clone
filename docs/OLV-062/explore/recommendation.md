# OLV-062 Explore: Recommendation

## 선택: Option 2 (메서드 분리)

### 이유
1. **MVP 범위에 적합**: 관리자/사용자 경로 간 로직 재사용이 용이
2. **테스트 가능성**: 각 단계를 독립적으로 테스트 가능
3. **과잉 설계 회피**: 책임 체인이나 전략 패턴은 이 단계에서 불필요한 복잡성
4. **트랜잭션 경계 명확**: 단일 `@Transactional` 메서드로 원자성 보장

### 첫 번째 실패 테스트 (Happy Path)

```java
@SpringBootTest
@AutoConfigureMockMvc
class OrderCancelApiIT extends PostgresIntegrationSupport {

    @Test
    @Transactional
    void cancelOrder_fromPaidStatus_success() {
        // Given: PAID 상태의 주문
        Long orderId = createPaidOrder(memberId);
        String orderNo = orderRepository.findById(orderId).get().getOrderNo();
        Long memberCouponId = ...;
        BigDecimal pointUsed = ...;

        // When: 사용자가 주문 취소
        mockMvc.perform(post("/api/orders/{orderNo}/cancel", orderNo)
                .header("Authorization", "Bearer " + userAccessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\": \"단순 변심\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("CANCELED"));

        // Then:
        // 1. 주문 상태가 CANCELED로 변경
        Order order = orderRepository.findById(orderId).get();
        assertThat(order.getStatus()).isEqualTo(Order.OrderStatus.CANCELED);

        // 2. order_status_histories에 CANCELED 전이 기록
        List<OrderStatusHistory> histories = orderStatusHistoryRepository.findByOrderId(orderId);
        assertThat(histories).anyMatch(h ->
            h.getToStatus().equals("CANCELED") &&
            h.getChangedByKind() == ChangedByKind.USER);

        // 3. 재고 예약이 해제됨 (inventory_reservations.status = RELEASED)
        List<InventoryReservation> reservations = reservationRepository.findByOrderId(orderId);
        assertThat(reservations).allMatch(r -> r.getStatus() == InventoryReservation.ReservationStatus.RELEASED);

        // 4. 쿠폰이 복구됨 (member_coupons.status = ISSUED)
        if (memberCouponId != null) {
            MemberCoupon coupon = memberCouponRepository.findById(memberCouponId).get();
            assertThat(coupon.getStatus()).isEqualTo(MemberCouponStatus.ISSUED);
        }

        // 5. 포인트가 복구됨 (point_histories에 CANCEL 내역)
        List<PointHistory> pointHistories = pointHistoryRepository.findByOrderId(orderId);
        assertThat(pointHistories).anyMatch(h ->
            h.getChangeType() == ChangeType.CANCEL &&
            h.getAmount().compareTo(pointUsed) > 0); // 복구는 양수

        // 6. OrderCanceledEvent가 발행됨 (outbox_events)
        List<OutboxEvent> outboxEvents = outboxEventRepository.findByAggregateId(orderId.toString());
        assertThat(outboxEvents).anyMatch(e -> e.getEventType().equals("ORDER_CANCELED"));
    }
}
```

### 추가 실패 테스트 시나리오

1. **PAYMENT_PENDING 상태 취소**: PG 호출 없이 재고만 해제
2. **PREPARING 상태 취소**: PAID와 동일하게 전체 취소
3. **SHIPPING 상태 취소 (사용자)**: 422 ORDER_NOT_CANCELLABLE
4. **SHIPPING 상태 취소 (관리자)**: 성공, PG 취소 호출
5. **소유권 없는 주문 취소**: 403 ORDER_NOT_OWNED
6. **멱등성**: 동일 취소 요청 재시 시 200, 두 번째 PG 취소 없음

## 구현 파일 목록

1. **Service**
   - `order/OrderService.java` — `cancelUserOrder()`, `cancelAdminOrder()` 메서드 추가
   - `order/OrderCanceledEvent.java` — 취소 이벤트 클래스 생성

2. **Controller**
   - `order/OrderController.java` — `POST /api/orders/{orderNo}/cancel` 엔드포인트 추가
   - `admin/OrderAdminController.java` — `POST /api/admin/orders/{orderId}/cancel` 엔드포인트 추가

3. **DTO**
   - `order/OrderDtos.java` — `CancelOrderRequest`, `CancelOrderResponse` 추가

4. **Error Code**
   - `common/error/ErrorCode.java` — `ORDER_NOT_CANCELLABLE`, `ORDER_NOT_OWNED` 추가

5. **Mock PaymentService (OLV-072까지 임시)**
   - `payment/MockPaymentService.java` — 취소 모의 구현

6. **Test**
   - `src/test/java/com/olive/commerce/order/OrderCancelApiIT.java` — 통합 테스트

## 구현 순서 (TDD)

1. **OrderCanceledEvent** 클래스 생성
2. **첫 번째 실패 테스트 작성** (PAID 상태 취소 happy path)
3. **OrderService.cancelUserOrder()** 메서드 구현 (PG 호출 제외)
4. **OrderController** 사용자 취소 엔드포인트 구현
5. **Mock PaymentService**로 테스트 통과
6. **관리자 취소 엔드포인트** 구현
7. **나머지 실패 시나리오 테스트** 작성 및 통과

## 트랜잭션 설계

```
@Transactional
public void cancelUserOrder(Long memberId, String orderNo, String reason) {
    // 1. 주문 조회 (read-only)
    Order order = findAndValidateOwnership(memberId, orderNo);

    // 2. 취소 가능 상태 검증 (throws ORDER_NOT_CANCELLABLE)
    validateCancellableStatus(order, CancelKind.USER);

    // 3. 취소 실행 (하위 도메인 호출)
    executeCancel(order, reason, CancelKind.USER);

    // 4. 상태 전이 + 이력 기록
    order.toCanceled(reason);
    orderStatusHistoryRepository.save(...);

    // 5. 이벤트 발행 (outbox)
    outboxEventRepository.save(...);
}

private void executeCancel(Order order, String reason, CancelKind kind) {
    // 3-1. PG 취소 (PAID 상태인 경우만)
    if (order.getStatus() == PAID || order.getStatus() == PREPARING) {
        paymentService.cancelPayment(...);
    }

    // 3-2. 재고 해제 (이미 release된 경우 idempotent)
    inventoryService.release(order.getId(), reason);

    // 3-3. 쿠폰 복구
    if (order.getUsedMemberCouponId() != null) {
        couponService.restore(order.getUsedMemberCouponId(), order.getId());
    }

    // 3-4. 포인트 복구
    pointService.cancel(order.getMemberId(), order.getId());
}
```

## 멱등성 보장

1. **상태 체크**: 이미 CANCELED 상태면 early return
2. **PG 멱등성**: OLV-072에서 PG idempotency key 활용
3. **하위 도메인 멱등성**: `InventoryService.release()`, `CouponService.restore()`, `PointService.cancel()`은 이미 idempotent하게 구현됨

## 실패 복구 전략

PG 취소 실패 시:
1. 트랜잭션 롤백 (주문 상태 변경되지 않음)
2. 사용자에게 명확한 에러 메시지 반환
3. 관리자 알림 (batch로 재시도)

하위 도메인 실패 시:
- 모두 `RuntimeException`이므로 트랜잭션 자동 롤백
- 복구 로직 불필요 (원자성 보장)
