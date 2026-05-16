# OLV-074: 환불 API 구현

## 개요

사용자 환불 요청, 관리자 승인/거절, PG 환불 호출을 포함한 환불 기능을 구현했습니다.

## 구현 내용

### 1. 사용자 환불 요청 API

- **Endpoint**: `POST /api/me/orders/{orderNo}/refund-request`
- **조건**: 
  - 본인 주문만 가능
  - 주문 상태가 `DELIVERED`여야 함
  - 결제 상태가 `APPROVED`여야 함
- **동작**:
  - 환불 가능 금액 계산 (총 결제 금액 - 기존 승인된 환불 금액)
  - `refunds` 테이블에 `REQUESTED` 상태로 환불 생성
  - 주문 상태를 `REFUND_REQUESTED`로 변경
  - 주문 상태 이력 기록

### 2. 관리자 환불 승인 API

- **Endpoint**: `POST /api/admin/refunds/{refundId}/approve`
- **권한**: `ORDER_ADMIN` 필요
- **동작** (단일 트랜잭션):
  1. PG 환불 API 호출
  2. `refunds.status = APPROVED` 변경
  3. `payments.status = REFUNDED` 변경
  4. `orders.status = REFUNDED` 변경
  5. 재고 복구 (`InventoryService.adjust`)
  6. 포인트 복구 (`PointService.cancel`)
  7. Outbox 이벤트 생성 (`ORDER_REFUNDED`)
  8. Spring 이벤트 발행
  9. 감사 로그 기록
- **멱등성**: 이미 `APPROVED` 상태면 no-op (PG 재호출 방지)

### 3. 관리자 환불 거절 API

- **Endpoint**: `POST /api/admin/refunds/{refundId}/reject`
- **권한**: `ORDER_ADMIN` 필요
- **동작**:
  - `refunds.status = FAILED` 변경
  - 주문 상태를 `DELIVERED`로 복구
  - 거절 사유 기록
- **멱등성**: 이미 `FAILED` 상태면 no-op

### 4. 관리자 환불 목록 조회 API

- **Endpoint**: `GET /api/admin/refunds?status=&page=&size=`
- **권한**: `ORDER_ADMIN` 필요
- **기능**: 상태별 필터링, 페이징 지원

## 수정된 파일

| 파일 | 설명 |
|------|------|
| `Refund.java` | 환불 엔티티, 테스트용 setId 추가 |
| `Payment.java` | 테스트용 setId 추가 |
| `Order.java` | 테스트용 setId 추가 |
| `RefundService.java` | Map.of() → HashMap 변경 (null 값 지원) |
| `RefundServiceTest.java` | 테스트 고정 (ID 설정, assertion 수정) |

## 기술적 결정 사항

1. **HashMap 사용**: `Map.of()`는 null 값을 허용하지 않아 `HashMap`으로 변경
2. **테스트용 setId**: 엔티티에 테스트 전용 ID 설정자 추가
3. **단일 트랜잭션**: 승인 시 모든 side effect를 하나의 트랜잭션으로 처리
4. **멱등성**: 상태 기반 early return으로 중복 실행 방지

## 테스트 커버리지

- `requestRefund_DELIVERED주문_환불요청_성공`: ✅
- `requestRefund_타인주문_거절`: ✅
- `requestRefund_DELIVERED아니면_거절`: ✅
- `approveRefund_PG호출_재고복구_포인트복구`: ✅
- `approveRefund_이미승인된_면등성`: ✅
- `rejectRefund_거절성공_주문상태복구`: ✅
- `rejectRefund_이미거절된_면등성`: ✅
- `listRefunds_STATUS필터링`: ✅

## 추후 작업

- 부분 환불 시 포인트/쿠폰 비례 계산 (현재는 전체 환불만 지원)
- 환불 웹훅 경로 완전 구현 (OLV-090)
