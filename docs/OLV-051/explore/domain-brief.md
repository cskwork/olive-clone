# OLV-051 Domain Brief: Coupon Admin + Member Coupon APIs

## 의존성 확인

| 의존항 | 상태 | 비고 |
|-------|------|------|
| OLV-050 (Promotion Schema) | ✅ Done | V6 마이그레이션 적용됨: `coupons`, `member_coupons`, `promotions`, `promotion_products`, `points`, `point_histories` |
| OLV-005 (Member Auth) | ✅ Done | `members` 테이블 존재, MemberRole enum: `USER` | `CS_MANAGER` | `PRODUCT_ADMIN` | `ORDER_ADMIN` | `SUPER_ADMIN` |
| OLV-020 (Product) | ✅ Done | `products`, `product_options` 테이블 존재 — 쿠폰 상품 적용 가능성 검증 필요 |

## 스키마 핵심 사항 (V6__promotion.sql)

### coupons 테이블
```sql
discount_type IN ('FIXED_AMOUNT', 'PERCENTAGE', 'FREE_SHIPPING', 'BUY_ONE_GET_ONE', 'MEMBER_GRADE')
status IN ('ACTIVE', 'INACTIVE')
issued_count <= max_issue_count (constraint 강제)
```

### member_coupons 테이블
```sql
status IN ('ISSUED', 'USED', 'EXPIRED', 'REVOKED')
uniq_member_coupons_member_coupon (member_id, coupon_id) WHERE status = 'ISSUED'
```
- UNIQUE 인덱스는 한 회원이 같은 쿠폰을 중복 발급받지 못하게 막음
- 하지만 AC에서 요구하는 "max_issue_count=500일 때 1000명 중 500명만 발급"은 `coupons.issued_count`와 연계해서 처리해야 함

### PK/FK 관계
- `member_coupons.member_id` → `members(id)` CASCADE
- `member_coupons.coupon_id` → `coupons(id)` CASCADE
- `used_order_id`는 BIGINT NULLABLE (FK 없음, OLV-060에서 주문 테이블 생성 예정)

## 기존 코드 패턴 분석

### Admin 컨트롤러 패턴 (InventoryAdminController 참고)
```java
@RestController
@RequestMapping("/api/admin/inventories")
@PreAuthorize("hasRole('PRODUCT_ADMIN')")  // 또는 SUPER_ADMIN
public class InventoryAdminController {
    private final InventoryService inventoryService;  // constructor injection
    @GetMapping / @PostMapping
    public ApiResponse<T> endpoint(...) { ... }
}
```

### 서비스 계층 패턴
- Entity는 `protected` 기본 생성자 + static 팩토리 메서드 (`create()`, `createWithStatus()`)
- Repository는 Spring Data JPA `JpaRepository<Entity, Long>`
- BusinessException은 `ErrorCode` enum + detail message

### 감사 로그 패턴 (OLV-011)
```java
auditLogger.log("ADMIN_MUTATION", Map.of(
    "adminId", adminId,
    "action", "CREATE_COUPON",
    "couponId", couponId,
    "name", name
));
```

### ErrorCode enum
- 기존: `COUPON_INVALID(HttpStatus.BAD_REQUEST)` 이미 정의됨
- 추가 필요: 쿠폰 관련 상세 에러 코드?

## OLV-061 (Order) 의존사항 확인

티켓 설명: "Order creation pipeline depends on CouponService.validate() and CouponService.use()"

Service contract 요구사항:
1. `validate(memberId, couponId, orderAmount, productIds): ValidatedCoupon`
   - throws `BusinessException(COUPON_INVALID, reason)`
   - reason ∈ `EXPIRED | NOT_OWNED | ALREADY_USED | MIN_AMOUNT_NOT_MET | NOT_APPLICABLE_PRODUCT`

2. `markUsed(memberCouponId, orderId)` — order-create 트랜잭션 내에서 호출

3. `restore(memberCouponId, orderId)` — 주문 취소 시 호출

4. `tryReserve(memberId, couponId, cartTotal)` — OLV-061에서 호출할 예정

## 동시성 제어 요구사항 (AC)

"Bulk issue: 1000 member ids + max_issue_count=500 → exactly 500 member_coupons rows"

힌트: "Use a row-level lock (`SELECT ... FOR UPDATE`) on `coupons` during bulk issue"

→ JPA `@Lock(LockModeType.PESSIMISTIC_WRITE)` 또는 native query `FOR UPDATE` 사용 필요

## 기술 스택 확인

- Java 21, Spring Boot 3.x
- Spring Data JPA, QueryDSL (미설정 시 추가 필요)
- Testcontainers (Postgres)
- Validation: Jakarta `@Valid`, Bean Validation

## 미해결 질문

1. **Coupon/applicableProducts 관계**: PRD §7.8에 "쿠폰 적용 가능 상품" 제한이 있는가?
   - V6 스키마에는 `coupon_products` 테이블이 없음
   - AC의 `NOT_APPLICABLE_PRODUCT` 검증은 어떻게 구현?
   - **임시 결정**: 전체 상품 적용으로 시작, 추후 product restriction 추가

2. **Percentage 쿠폰 최대 할인 금액**: 시드 데이터 "10% 할인 쿠폰 (최대 5000원)"에서 확인됨
   - 스키마에는 `max_discount_amount` 컬럼이 없음
   - **임시 결정**: V7 마이그레이션에서 추가 또는 애플리케이션 레벨 계산

3. **BOGO 쿠폰 구현**: `discount_value`가 100인 경우 "buy 1, get 1 free"로 해석?
   - AC에는 BOGO 검증이 없음
   - **임시 결정**: FIXED_AMOUNT/PERCENTAGE만 먼저 구현

4. **Member ID 리스트 발급 API**: `POST /api/admin/coupons/{id}/issue`
   - 요청 본문: `List<Long> memberIds` 또는 `IssueRequest { memberIds, message }`?
   - 응답: 성공/실패 상세 또는 단순 count?
   - **임시 결정**: `IssueRequest { List<Long> memberIds }` → `IssueResponse { successCount, failedCount, failedMemberIds }`
