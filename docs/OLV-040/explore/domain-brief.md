# Cart Domain Brief (OLV-040)

## Cart Domain Overview

Cart는 사용자의 구매 의도를 일시적으로 저장하는 staging 영역입니다. PRD §6.4에 따르면:
- "장바구니는 편의성 저장소일 뿐, 출처(source of truth)가 아니다"
- 주문 생성 시 product/inventory 도메인에서 가격과 재고를 재검증해야 함

## Schema Requirements

### 1. carts 테이블 (회원용)
```sql
CREATE TABLE carts (
    id BIGSERIAL PRIMARY KEY,
    member_id BIGINT NOT NULL UNIQUE REFERENCES members(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
```
- `member_id UNIQUE`: 한 회원당 하나의 카트
- `ON DELETE CASCADE`: 회원 탈퇴 시 카트 자동 삭제

### 2. cart_items 테이블
```sql
CREATE TABLE cart_items (
    id BIGSERIAL PRIMARY KEY,
    cart_id BIGINT NOT NULL REFERENCES carts(id) ON DELETE CASCADE,
    product_option_id BIGINT NOT NULL REFERENCES product_options(id) ON DELETE CASCADE,
    quantity INTEGER NOT NULL DEFAULT 1 CHECK (quantity > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (cart_id, product_option_id)
);
```
- `UNIQUE (cart_id, product_option_id)`: 동일 옵션 중복 추가 방지 (수량 증분)
- `quantity > 0 CHECK`: 0 이하 불가

### 3. Anonymous Cart (Redis)
- Key: `cart:anon:{sessionId}`
- Value: JSON array of items
- TTL: 30 days (2,592,000 seconds)
- Structure:
```json
[
  {"productOptionId": 1, "quantity": 2},
  {"productOptionId": 5, "quantity": 1}
]
```

## API Requirements

### POST /api/cart/items
Request: `{"productOptionId": 1, "quantity": 2}`
- 이미 카트에 있으면 수량 증분 (아니면 새 row)
- 실패 조건:
  - ProductOption.status in (STOPPED, HIDDEN) → 400
  - Inventory.available_quantity < requested → 409 + available count

### GET /api/cart
Response: items with latest price/status
```json
{
  "success": true,
  "data": [
    {
      "cartItemId": 1,
      "productOptionId": 1,
      "optionName": "50ml",
      "salePrice": 20000,
      "onSale": true,
      "availableQuantity": 15,
      "quantity": 2,
      "lineSubtotal": 40000,
      "productStatus": "ON_SALE"
    }
  ]
}
```
- `productStatus != "ON_SALE"`이면 UI가 삭제 프롬프트

### PATCH /api/cart/items/{cartItemId}
Request: `{"quantity": 3}`
- 재검증: available_quantity >= new_quantity

### DELETE /api/cart/items/{cartItemId}
- 단일 item 삭제

### POST /api/cart/merge
- 로그인 흐름에서 호출
- Anonymous(Redis) → Member(DB) 병합
- 로직: union by product_option_id, sum quantities, cap at available_quantity

## Integration Points

1. **Member 도메인** (OLV-011):
   - AuthController.login → merge 호출 필요
   - Member.id FK

2. **Product 도메인** (OLV-022):
   - ProductOption.status 검증
   - Product, ProductOption 조인으로 최신 가격/이름 조회

3. **Inventory 도메인** (OLV-030):
   - Inventory.available_quantity로 재고 검증
   - InventoryRepository.findByProductOptionId

## Implementation Order

1. Schema: V5__cart.sql
2. Entities: Cart, CartItem
3. Repositories: CartRepository, CartItemRepository
4. DTOs: CartDtos (request/response)
5. Service: CartService (business logic + Redis merge)
6. Controller: CartController
7. Integration Test: CartApiIT

## Error Codes Needed

- CART_NOT_FOUND
- CART_ITEM_NOT_FOUND
- CART_ITEM_INVALID_OPTION (STOPPED|HIDDEN)
- INSUFFICIENT_INVENTORY (재사용, available count 포함)
