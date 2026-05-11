# OLV-070 Payment Schema Implementation Details

## Files Changed

### 1. V9__payment.sql (139 lines)

**Location**: `src/main/resources/db/migration/V9__payment.sql`

**Key Design Decisions**:

1. **payments 테이블**:
   - `order_id UNIQUE NOT NULL REFERENCES orders(id)`: 1:1 관계, 주문당 결제 하나
   - `idempotency_key UUID UNIQUE NOT NULL`: 클라이언트 재시도 시 중복 결제 방지 (PRD §20.4)
   - `payment_key VARCHAR(255) NULLABLE`: PG측 키, 승인 전까지 NULL (결제 요청 시점에는 아직 PG 키 없음)
   - `status CHECK (READY | REQUESTED | APPROVED | FAILED | CANCELED | REFUNDED)`: VARCHAR + CHECK로 future-proof
   - `method CHECK (CARD | KAKAO_PAY | NAVER_PAY | TOSS_PAY | VIRTUAL_ACCOUNT)`: 결제 수단 enum
   - `requested_at NOT NULL`, `approved_at NULLABLE`: 승인 전후 시각

2. **payment_transactions 테이블**:
   - `pg_response_json JSONB`: PG 응답 전체를 JSONB로 저장 (사후 디버깅용)
   - `idempotency_key UUID NULLABLE`: NULL 허용 (PG 웹훅은 클라이언트 키 없음)
   - `UNIQUE (payment_id, kind, idempotency_key)`: 재실행(replay) 방지 핵심 제약조건 (AC2)

3. **refunds 테이블**:
   - `payment_id REFERENCES payments(id)`, `order_id REFERENCES orders(id)`: 이중 FK
   - `amount DECIMAL(12,2) CHECK (amount > 0)`: 환불 금액은 0 초과
   - `status CHECK (REQUESTED | APPROVED | FAILED)`: 환불 상태
   - `requested_at NOT NULL`, `approved_at NULLABLE`: requested_at 사용 (created_at 아님)

**Indexes**:
- `idx_payments_status_created`: 결제 대기 만료 배치 처리용
- `idx_payments_payment_key`: PG 웹훅 조회용 (WHERE payment_key = ?)
- `idx_payment_transactions_payment_created`: 결제 트랜잭션 이력 조회
- `idx_refunds_payment_id`: 결제별 환불 목록
- `idx_refunds_order_id`: 주문별 환불 요약
- `idx_refunds_status_requested`: 환불 처리 배치용

### 2. PaymentSchemaIntegrationTest.java (365 lines)

**Location**: `src/test/java/com/olive/commerce/payment/PaymentSchemaIntegrationTest.java`

**Test Coverage**:

| 테스트 메서드 | 검증 내용 |
|--------------|-----------|
| v9MigrationIsApplied | flyway_schema_history에 V9 존재 |
| paymentsTableExistsWithConstraints | status/method CHECK, idempotency_key UNIQUE, order_id UNIQUE |
| paymentTransactionsTableExistsWithConstraints | kind CHECK, pg_response_json JSONB, UNIQUE 제약 |
| refundsTableExistsWithConstraints | status CHECK, amount > 0 CHECK |
| repositoryTest_FullLifecycle_InsertSelect | order → payment REQUEST → APPROVE → WEBHOOK → refund 전체 플로우 |
| ac2_ReplayProtection_UniqConstraintViolatesOnDuplicate | 동일 (payment_id, kind, idempotency_key) 삽입 시 제약 위반 |
| idx_Payments_StatusCreatedAt_Exists | idx_payments_status_created 인덱스 |
| idx_Payments_PaymentKey_Exists | idx_payments_payment_key 인덱스 |
| idx_PaymentTransactions_PaymentCreatedAt_Exists | idx_payment_transactions_payment_created 인덱스 |
| idx_Refunds_PaymentId_Exists | idx_refunds_payment_id 인덱스 |
| idx_Refunds_OrderId_Exists | idx_refunds_order_id 인덱스 |
| idx_Refunds_StatusRequestedAt_Exists | idx_refunds_status_requested 인덱스 |

**Acceptance Criteria Coverage**:
- AC1: V8(V9) applied; integration test inserts full lifecycle → `repositoryTest_FullLifecycle_InsertSelect`
- AC2: Replay protection UNIQUE constraint → `ac2_ReplayProtection_UniqConstraintViolatesOnDuplicate`

## Implementation Notes

### 수정 이력

1. **V9 넘버링**: 티켓 명세에서는 V8__payment.sql을 요청했으나, V8은 outbox_events(OLV-100)에서 이미 사용 중이므로 V9__payment.sql로 수정.

2. **refunds 인덱스 수정**: `idx_refunds_status_created` → `idx_refunds_status_requested`. refunds 테이블은 `created_at` 대신 `requested_at` 컬럼을 사용하므로 인덱스도 이에 맞춰 수정.

3. **테스트 refunds INSERT 수정**: `idempotency_key` 컬럼 제거. refunds 테이블 명세에는 idempotency_key가 없음 (PRD §7.7).

### DECIMAL(12,2) 사용

돈 금액은 모두 `DECIMAL(12,2)` 타입 사용:
- 최대 999,999,999.99 (약 10억 원)
- Java에서는 `BigDecimal`로 매핑

### JSONB 활용

`payment_transactions.pg_response_json`을 JSONB로 저장:
- 장점: 사후 디버깅 시 별도 로그 없이 원본 PG 응답 확인 가능
- JSONB 연산자로 쿼리 가능 (예: `pg_response_json->>'payment_key'`)

### 멱등성(Idempotency) 구조

1. **payments.idempotency_key**: 클라이언트가 결제 요청 시 제공, UUID UNIQUE NOT NULL
2. **payment_transactions**: (payment_id, kind, idempotency_key) UNIQUE로 재실행 방지

이 구조로 클라이언트가 동일한 idempotency_key로 재시도해도:
- payments 테이블에는 중복 행 생성 안 됨
- payment_transactions에도 동일 트랜잭션 재기록 안 됨

## Test Results

```
PaymentSchemaIntegrationTest
  tests=12, skipped=0, failures=0, errors=0
  execution time: ~0.5s
```

All 12 tests passed:
- V9 migration applied
- 3 tables exist with constraints
- Idempotency keys enforced
- Replay protection UNIQUE constraint works
- Full lifecycle (request → approve → webhook → refund) verified
- 6 indexes created
