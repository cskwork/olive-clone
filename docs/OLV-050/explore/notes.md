# OLV-050 Explore: Promotion Schema

## FK Dependencies 확인

| Table | FK References | Status |
|-------|---------------|--------|
| `member_coupons.member_id` | `members(id)` | ✅ V2에서 생성됨 |
| `promotion_products.product_id` | `products(id)` | ✅ V3에서 생성됨 |
| `member_coupons.used_order_id` | `orders(id)` | ⚠️ 아직 생성 안 됨 (OLV-060) |

**Decision**: `used_order_id`는 FK 제약조건 없이 BIGINT NULLABLE로만 정의. 추후 OLV-060에서 orders 테이블 생성 후 ALTER로 추가 가능.

## Enum 타입: TEXT + CHECK 제약조건 패턴

기존 마이그레이션(V2, V3, V5)의 패턴:
```sql
status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
CONSTRAINT table_status_check CHECK (status IN ('ACTIVE', 'INACTIVE', ...))
```

이 패턴을 따름:
- `coupons.status`: ACTIVE | INACTIVE
- `member_coupons.status`: ISSUED | USED | EXPIRED | REVOKED
- `coupons.discount_type`: FIXED_AMOUNT | PERCENTAGE | FREE_SHIPPING | BUY_ONE_GET_ONE | MEMBER_GRADE
- `promotions.type`: TIME_DEAL | EVENT | CATEGORY_SALE
- `point_histories.change_type`: EARN | USE | CANCEL | EXPIRE | ADMIN_ADJUST

## Index 요구사항 (AC)

AC에서 명시된 인덱스:
1. `member_coupons(member_id, status)` — 회원별 쿠폰 목록 조회
2. `point_histories(member_id, available_at, expires_at)` — 사용 가능 포인트 조회

추가 고려 인덱스:
- `coupons(status, started_at, ended_at)` — 활성 쿠폰 목록
- `member_coupons(coupon_id)` — 쿠폰별 발급 현황
- `point_histories(member_id, created_at DESC)` — 최신 적립내역

## points.balance 설계

Wiki(50-promotion-domain.md) 결정: "Points are ledger-only, no cached balance column."

하지만 티켓 힌트에서는 "Include it but document the recompute path"라고 지시.

**Decision**: `points.balance` 컬럼을 포함하되, COMMENT로 "Cached rollup. Source of truth is point_histories. Recompute: SUM(amount) WHERE available_at <= now() AND (expires_at IS NULL OR expires_at > now())" 문서화.

## 시드 데이터 고려사항

프로모션 도메인은 시드 데이터가 필수는 아니지만, 테스트를 위해:
- 샘플 쿠폰 1건 (FIXED_AMOUNT, 정액 3000원 할인)
- 샘플 프로모션 1건 (TIME_DEAL)

선택사항으로 추가 가능.
