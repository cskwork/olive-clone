# OLV-030 Explore Details

## 인바리언트 상세 (llm-wiki/30-inventory-domain.md)

**Per-option inventory**
- 재고는 `product_option_id` 단위로 관리 (PRD §20.3)
- 한 상품의 여러 옵션(50ml, 100ml)은 각각 독립적인 재고를 가짐

**Reserve-then-commit 필수 (PRD §6.7 방식 2, §20.5)**
1. 주문 생성 → 재고 예약 (reserve), TTL 설정 (15분)
2. 결제 승인 → 커밋 (total_quantity 감소, reserved_quantity 감소, 예약 삭제)
3. 결제 실패/TTL 만료 → 릴리스 (reserved_quantity 감소)

**동시성 제어 (PRD §10.2)**
- 기본: Redis 분산 락 (Redisson RLock, key=lock:inv:{product_option_id}, lease 5s, wait 2s)
- 폴백: Redis 다운 시 `SELECT ... FOR UPDATE` 행 락 (PRD §15.4)

**감사 가능성**
- 모든 reserve/commit/release는 `inventory_histories`에 기록
- (reason, order_id, delta, ts)로 예약 원장 추적 가능

## 위험 분석

**R1: GENERATED 컬럼의 Postgres 버전 의존성**
- `available_quantity GENERATED ALWAYS AS ... STORED`는 Postgres 12+ 필요
- docker-compose는 `postgres:16-alpine` 사용 (V1 baseline 확인) → 문제 없음
- Testcontainers도 16 사용 (build.gradle.kts 확인 필요)

**R2: UNIQUE 제약 조건 누락 위험**
- AC2: "(order_id, product_option_id) 중복 삽입 금지" 필요
- 이 제약이 없으면 같은 주문+옵션 조합으로 여러 예약이 생성 가능 → 데이터 무결성 훼손

**R3: FK ON DELETE 정책**
- `product_options`를 참조하므로 `ON DELETE RESTRICT` 필요
- 옵션 삭제 전 재고를 0으로 만드는 워크플로우 강제

## 기존 마이그레이션 패턴 분석

**V2__member.sql 패턴**
- `set_updated_at()` 트리거 함수 정의 (재사용)
- FK 제약에 `ON DELETE CASCADE`/`SET NULL` 명시
- CHECK 제약으로 status enum 강제
- COMMENT ON TABLE/COLUMN으로 문서화
- 시드 데이터 INSERT 포함

**V3__product.sql 패턴**
- 6개 테이블을 한 V3 파일에 담음 (분리하지 않음)
- `product_options` 테이블 정의 (id BIGSERIAL PRIMARY KEY)
- `idx_product_options_product_id` 인덱스

**V4__inventory.sql에 적용할 패턴**
- `set_updated_at()` 트리거 재사용 (V2 정의)
- 3개 테이블(inventories, inventory_histories, inventory_reservations)을 V4 한 파일에 담음
- `product_options.id`를 참조하는 FK에 `ON DELETE RESTRICT`
- CHECK 제약으로 quantity non-negative 강제
- UNIQUE 인덱스로 (order_id, product_option_id) 중복 방지 (AC2)
- COMMENT로 문서화

## PRD 섹션 참조

| 섹션 | 내용 |
|------|------|
| §6.7 | 재고 관리 방식 2: 예약 후 커밋 |
| §7.4 | inventories / inventory_histories / inventory_reservations 테이블 정의 |
| §10.2 | 동시성 제어: Redis 분산 락 |
| §15.4 | Redis 장애 시 DB 폴백: SELECT FOR UPDATE |
| §17.2 | 배치 작업: 예약 만료 배치 (5분마다) |
| §20.3 | 재고는 product_option_id 단위 |
| §20.5 | 주문-재고 연동: reserve → commit flow |

## change_type enum 값

PRD §7.4에 명시된 값들:
- `STOCK_IN`: 입고
- `STOCK_OUT`: 출고 (반품, 파손 등)
- `RESERVE`: 예약
- `COMMIT`: 커밋 (결제 승인)
- `RELEASE`: 릴리스 (예약 취소/만료)
- `ADMIN_ADJUST`: 관리자 수동 조정

## status enum 값 (inventory_reservations)

PRD §7.4에 명시된 값들:
- `HELD`: 예약 보유 중 (결제 대기)
- `COMMITTED`: 커밋 완료 (결제 승인 후, finalized_at 기록)
- `RELEASED`: 릴리스 완료 (취소/만료)
