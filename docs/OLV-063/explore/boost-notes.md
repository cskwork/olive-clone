# OLV-063 Explore Boost Notes

## 기존 코드 확인

### 1. Order 도메인 상태 (OLV-061 완료)
- `Order.java`: 상태 전이 머신 구현됨
- `OrderRepository.java`: `findByMemberIdOrderByCreatedAtDesc`, `findByOrderNo`, `findByIdempotencyKey`
- `OrderService.java`: 주문 생성, 회원 취소, 관리자 취소 구현됨
- `OrderController.java`: `POST /api/orders`, `POST /api/orders/{orderNo}/cancel`
- `OrderAdminController.java`: `POST /api/admin/orders/{orderId}/cancel`

### 2. 페이지네이션 패턴 (ProductAdminController 참조)
```java
@RequestParam(defaultValue = "0") int page,
@RequestParam(defaultValue = "20") int size
Page<T> result = service.list(..., page, size);
PageMeta meta = new PageMeta(page, size, result.getTotalElements());
return ApiResponse.success(result.getContent(), meta);
```

### 3. PII 마스킹 요구사항
- `member_addresses` 테이블: `recipient_name`, `phone`, `address_main`, `address_detail`
- PRD §14.3: 마스킹 규칙
  - 전화번호: `010-****-1234`
  - 이메일: `u***@example.com`
- 별도 "view PII" 권한 필요 (현재 미구현)

### 4. 상태 전이 규칙 (Order.java)
```
CREATED → PAYMENT_PENDING → PAID → PREPARING → SHIPPING → DELIVERED
                                  ↓
                             CANCELED / REFUND_REQUESTED / REFUNDED / FAILED
```

### 5. order_status_histories 테이블
- `changed_by_kind`: USER, ADMIN, SYSTEM
- `changed_by_id`: 회원 ID 또는 관리자 ID
- 감사 추적용

## 구현 필요 사항

### User Endpoints
1. `GET /api/me/orders?status=&page=&size=`
   - 인증된 회원의 주문 목록
   - status 필터 (선택)
   - created_at DESC 정렬
   - 페이지네이션

2. `GET /api/me/orders/{orderNo}`
   - 주문 상세 (items, price summary, delivery snapshot, status history)
   - 소유권 검증 (order.memberId == principal.id)

### Admin Endpoints
1. `GET /api/admin/orders?status=&memberId=&from=&to=&page=&size=`
   - 전체 주문 목록
   - 다중 필터 (status, memberId, date range)
   - PII 마스킹 (recipient_name, phone, address)
   - 페이지네이션

2. `GET /api/admin/orders/{orderId}`
   - 주문 상세 (모든 정보)
   - status history 포함

3. `PATCH /api/admin/orders/{orderId}/status`
   - 상태 전이 (`{toStatus, reason}`)
   - 전이 검증 (`PAID → DELIVERED` 불가)
   - `order_status_histories` 기록
   - 감사 로그

## PII 마스킹 구현 방안

### Option A: DTO에서 마스킹 (선택)
```java
if (!hasViewPiiPermission) {
    return new AdminOrderListResponse(
        ...,
        maskName(address.getRecipientName()),
        maskPhone(address.getPhone()),
        maskAddress(address.getAddressMain())
    );
}
```

### Option B: Projection Interface (Spring Data JPA)
```java
interface OrderSummaryProjection {
    Long getId();
    String getOrderNo();
    // masked fields...
}
```

### Option C: Mapper/Converter (권장)
- `OrderMapper.toMaskedResponse()` for list view
- `OrderMapper.toFullResponse()` for detail view
- 권한에 따라 분기

## 상태 전이 검증

Order 엔티티의 `isValidTransition()` 메서드를 재사용:
- `PAID → PREPARING`
- `PREPARING → SHIPPING`
- `SHIPPING → DELIVERED`
- `DELIVERED → REFUND_REQUESTED`
- 역방향/단계 건너뛰기는 `IllegalStateException`

### 관리자 전이 규칙 (PRD §8.5)
- `PAYMENT_PENDING/PAID/PREPARING → CANCELED` (강제 취소 - 이미 구현됨)
- `PAID → PREPARING`
- `PREPARING → SHIPPING`
- `SHIPPING → DELIVERED`
- `DELIVERED → REFUND_REQUESTED`
- `REFUND_REQUESTED → REFUNDED`
