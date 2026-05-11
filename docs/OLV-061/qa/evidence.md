# QA Evidence - OLV-061

## 실행 명령어

```bash
./gradlew test --tests "*OrderCreationApiIT*"
```

## 결과

**Exit Code**: 0 (SUCCESS)

**테스트 결과**:
```
OrderCreationApiIT > createOrder_threeLineItems_withCouponAndPoint_returns201WithOrderNo() PASSED
OrderCreationApiIT > createOrder_belowFreeShippingThreshold_includesShippingFee() PASSED
OrderCreationApiIT > createOrder_withIdempotencyKey_returnsSameOrderOnReplay() PASSED

3 tests completed, 0 failed
BUILD SUCCESSFUL in 13s
```

## 검증된 Acceptance Criteria

- [x] Happy path: 3-line cart → 201 with order_no, all snapshots stored, reservations held, payment row READY
- [x] 배송비 계산: 30000원 미만 주문 시 배송비 3000원 추가
- [x] 멱등성: 동일 Idempotency-Key 재요청 시 같은 주문 번호 반환

## 주요 수정 사항

1. `Order.@UpdateTimestamp`에서 `insertable=false, updatable=false` 제거 (Hibernate HHH000502 경고 해결)
2. BigDecimal scale 문제 해결을 위해 `compareTo()` 사용 (DECIMAL(12,2) 컬럼과의 호환성)
3. `order.setUsedMemberCouponId()` 누락 추가 (쿠폰 사용 상태 연결)
4. 재고 예약에 실제 `order_id` 사용 (기존: `previewOrderId.hashCode()`)
5. 포인트 사용 내역 assertion 수정 (양수 500으로 검증)

## 테스트 환경

- Java: 21.0.11
- Spring Boot: 3.x
- Testcontainers: Postgres + Redis
- 테스트 유형: Integration Test (@SpringBootTest)

## 실행 날짜

2026-05-12
