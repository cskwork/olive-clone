# Order Domain

## Summary

주문 도메인은 회원의 장바구니에서 결제까지의 전체 흐름을 담당합니다. 8단계 주문 생성 파이프라인(PRD §8.3)을 구현하고, 재고 선점(reserve-then-commit) 패턴을 통해 동시성을 제어합니다.

## Invariants

1. **재고 부족 시 ZERO persisted**: `OrderService.create()`의 Step 3(재고 예약) 실패 시 `@Transactional`에 의해 주문/결제/쿠폰/포인트 변경사항이 모두 롤백됩니다.
2. **쿠폰/포인트는 검증 후 사용**: Step 4-5에서 검증만 수행하고, Step 4-8 통과 후에야 실제 사용(`markUsed`, `use`)을 호출하므로 롤백 시 자동 복구됩니다.
3. **주문 스냅샷 불변**: `order_items`에 상품명/옵션명/가격을 복사하여 저장하므로, 원본 상품이 변경되어도 주문 내역은 재현 가능합니다.

## Files of Interest

- `order/OrderService.java` — 8단계 주문 생성 파이프라인 구현 (OLV-061)
- `order/OrderController.java` — `POST /api/orders` 엔드포인트
- `order/Order.java` — 주문 엔티티 (status transition 메서드 포함)
- `order/OrderItem.java` — 주문 상품 엔티티 (snapshot 필드 포함)
- `order/OrderPriceSummary.java` — 가격 요약 엔티티
- `order/OrderStatusHistory.java` — 상태 변경 이력 엔티티
- `src/test/java/com/olive/commerce/order/OrderCreationApiIT.java` — 통합 테스트 (happy path + 실패 시나리오)

## Decision Log

### 2025-05-12: 8단계 주문 생성 파이프라인 구현 완료 (OLV-061)

**Context**: 회원이 장바구니에서 주문을 생성하면 상품/재고/쿠폰/포인트/배송지 도메인을 통합 검증하고, 주문과 결제 정보를 생성하는 API가 필요했습니다.

**Decision**: `OrderService.create()` 메서드에 8단계 파이프라인을 구현:
1. 회원 검증 (status = ACTIVE)
2. 상품 판매 상태 검증
3. 재고 검증 + 선점 (`InventoryService.reserve()`)
4. 쿠폰 검증 (`CouponService.validate()`)
5. 포인트 검증 (`PointService.spendableBalance()`)
6. 최종 금액 계산
7. 주문 생성 (`orders`, `order_items`, `order_price_summaries`, `order_status_histories`)
8. 결제 요청 정보 생성 (`payments` row with status READY)

**Trade-offs**:
- 단일 `@Transactional`로 Step 3-8을 감싸서 원자성 보장
- Step 3 실패 시 예약이 생성되지 않으므로 명시적 release 불필요
- Step 4-8 실패 시 catch에서 `inventoryService.release()` 호출

**Outcome**:
- Happy path + 3종 실패 시나리오 테스트 통과 (stock-out, invalid coupon, insufficient points)
- 롤백 검증을 위해 `@Transactional` 제거 + JdbcTemplate로 직접 DB 조회
- 동시성 테스트는 별도 티켓으로 분리 필요 (MockMvc 환경에서는 실제 race condition 재현 불가)

**Follow-up**:
- OLV-????: 동시성 테스트 (`@SpringBootTest(webEnvironment = RANDOM_PORT)` + TestRestTemplate)

## Last Updated

2025-05-12
