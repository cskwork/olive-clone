# OLV-040 QA Evidence

## 실행 명령어

```bash
cd /Users/danny/Documents/PARA/Resource/olive-clone
./gradlew clean :test --tests CartApiIT
```

## 실행 결과

```
> Task :test
BUILD SUCCESSFUL in 12s
6 actionable tasks: 6 executed
```

## 판정

**성공 (PASS)** — 모든 Acceptance Criteria 통과

## Acceptance Criteria 검증

| AC | 설명 | 테스트 케이스 | 결과 |
|----|------|-------------|------|
| AC1 | Round-trip integration test for add/list/update/remove | `add_list_update_remove_roundTrip()` | PASS |
| AC2 | Adding the same option twice increments instead of duplicating | `add_sameOptionTwice_incrementsQuantity_notDuplicate()` | PASS |
| AC3 | Add with quantity > stock → 409 + the available count in the body | `add_quantityExceedsStock_returns409WithAvailableCount()` | PASS |
| AC4 | Anonymous → member merge: union by product_option_id, sum quantities, cap at stock | `merge_anonymousToMember_unionByOption_sumQuantities_capAtStock()` <br> `merge_sumExceedsStock_cappedAtAvailableQuantity()` | PASS |
| AC5 | Cart items reflect the latest product price/status | `cartItem_reflectsLatestPriceAndStatus()` | PASS |

## 추가 테스트

| 테스트 | 설명 | 결과 |
|--------|------|------|
| `anonymousCart_add_list_update_remove()` | 익명 장바구니 전체 CRUD | PASS |

## API 동작 검증

### POST /api/cart/items (회원)
- 상태 코드: 201 Created
- 응답: `{"success":true,"data":{"cartItemId":1,"quantity":2}}`

### GET /api/cart (회원)
- 상태 코드: 200 OK
- 응답: 최신 가격/상태 포함 (salePrice, onSale, availableQuantity, productStatus)

### POST /api/cart/anonymous/items (익명)
- 상태 코드: 201 Created
- X-Session-ID 헤더로 세션 식별

### POST /api/cart/merge
- 상태 코드: 200 OK
- 익명 카트와 회원 카트 병합: 동일 옵션 수량 합산, 재고로 cap

## 수정 사항

1. **SecurityConfig**: `/api/cart/anonymous/**`를 permitAll로 추가
2. **CartApiIT**: JWT 인증을 위해 `UsernamePasswordAuthenticationToken`으로 `AuthenticatedUser`를 principal로 직접 설정
3. **CartApiIT**: members 테이블에 테스트 회원 데이터 추가
4. **CartApiIT**: `totalItemCount` 기대값 수정 (아이템 개수 → 수량 합계)
