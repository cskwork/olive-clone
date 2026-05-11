# OLV-070 Payment Schema Explore Details

## Domain Analysis

### 인바리언트 (llm-wiki/70-payment-domain.md)

1. **결제 상태 머신** (PRD §6.6):
   - READY → REQUESTED → APPROVED (정상 플로우)
   - REQUESTED → FAILED (실패)
   - APPROVED → CANCELED (취소)
   - APPROVED → REFUNDED (환불)

2. **멱등성(Idempotency) 필수** (PRD §20.4):
   - payments.idempotency_key: UUID UNIQUE NOT NULL
   - payment_transactions: (payment_id, kind, idempotency_key) UNIQUE
   - 동일한 idempotency_key로 재요청 시 부수 효과(side-effect) 없이 동일 결과 반환

3. **PG 콜백 우선** (PRD §6.6, §15.1):
   - 클라이언트側 성공 ≠ 결제 승인
   - 웹훅(또는 검증 API 호출)이 최종 진실 출처(source of truth)

4. **금액 검증** (PRD §14.4):
   - confirm 시 requested_amount == orders.final_payment_amount 검증
   - 불일치 시 거부 + 알림

5. **보안** (PRD §14.4):
   - 원본 카드 번호 절대 저장 금지
   - PG의 payment_key, pg_provider, transaction_id만 저장

### FK 관계

```
orders (V7)
  └── payments.order_id UNIQUE
       ├── payment_transactions.payment_id
       └── refunds.payment_id, refunds.order_id
```

## Plan Candidates

### A. V9__payment.sql 단일 마이그레이션 (권장)

**장점**:
- 원자성 보장 (3개 테이블이 동시에 생성됨)
- 후속 티켓이 단일 버전(V9)만 확인하면 됨
- 롤백 시 V8로의 복귀가 단순

**단점**:
- 없음 (스키마 변경은 append-only이므로 분리의 이득이 없음)

### B. 마이그레이션 분리 (V9_payments + V10_payment_transactions + V11_refunds)

**장점**:
- 없음

**단점**:
- 원자성 보장 어려움 (중간 단계에서 일관성 없는 상태)
- 후속 티켓이 3개 마이그레이션 모두 적용되었는지 확인해야 함

### C. payment_transactions.kind를 ENUM 타입으로

**장점**:
- DB 레벨에서 kind 값 제한

**단점**:
- 새로운 kind 추가 시 마이그레이션 필요 (PRD: "상태 추가는 코드 변경으로")
- VARCHAR(30) + CHECK 제약조건이 더 유연함

## Recommendation

**Option A 채택**:
1. V9__payment.sql 한 파일에 3개 테이블 + 인덱스 + 제약조건 포함
2. payment_method와 kind는 VARCHAR + CHECK 제약조건 (future-proof)
3. 첫 실패 테스트: `PaymentSchemaIntegrationTest.v9MigrationIsApplied`

## Test Strategy

### PaymentSchemaIntegrationTest 테스트 케이스

1. **v9MigrationIsApplied**: flyway_schema_history에 V9 존재 확인
2. **paymentsTableExistsWithConstraints**:
   - status CHECK 제약 (READY | REQUESTED | APPROVED | FAILED | CANCELED | REFUNDED)
   - idempotency_key UNIQUE
   - order_id UNIQUE (1:1 관계)
   - method CHECK (CARD | KAKAO_PAY | NAVER_PAY | TOSS_PAY | VIRTUAL_ACCOUNT)
3. **paymentTransactionsTableExists**:
   - pg_response_json JSONB 타입
   - UNIQUE (payment_id, kind, idempotency_key)
   - kind CHECK (REQUEST | APPROVE | CANCEL | WEBHOOK | REFUND)
4. **refundsTableExistsWithConstraints**:
   - status CHECK (REQUESTED | APPROVED | FAILED)
   - payment_id FK, order_id FK
5. **repositoryTest_FullLifecycle**:
   - order 생성 → payment REQUEST → payment APPROVE → refund REQUESTED → refund APPROVED
   - 각 단계에서 payment_transactions row 기록
6. **ac2_ReplayProtection_UniqConstraint**:
   - 동일 (payment_id, kind, idempotency_key)로 두 번 INSERT 시 UNIQUE 제약 위반 확인

## Schema Sketch (V9__payment.sql)

```sql
-- payments
CREATE TABLE payments (
    id                  BIGSERIAL PRIMARY KEY,
    order_id            BIGINT       NOT NULL UNIQUE REFERENCES orders(id),
    payment_key         VARCHAR(255),     -- PG-side unique key, NULLABLE until PG returns
    pg_provider         VARCHAR(50),
    method              VARCHAR(50)  NOT NULL,
    status              VARCHAR(30)  NOT NULL DEFAULT 'READY',
    requested_amount    DECIMAL(12,2) NOT NULL,
    approved_amount     DECIMAL(12,2),
    idempotency_key     UUID         NOT NULL UNIQUE,
    requested_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    approved_at         TIMESTAMPTZ,
    failed_reason       VARCHAR(255),
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT payments_status_check CHECK (status IN (...)),
    CONSTRAINT payments_method_check CHECK (method IN (...))
);

-- payment_transactions
CREATE TABLE payment_transactions (
    id                  BIGSERIAL PRIMARY KEY,
    payment_id          BIGINT       NOT NULL REFERENCES payments(id) ON DELETE CASCADE,
    kind                VARCHAR(30)  NOT NULL,
    pg_response_json    JSONB,
    http_status         INTEGER,
    idempotency_key     UUID,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT payment_transactions_kind_check CHECK (kind IN (...)),
    CONSTRAINT uq_payment_transaction_replay UNIQUE (payment_id, kind, idempotency_key)
);

-- refunds
CREATE TABLE refunds (
    id                  BIGSERIAL PRIMARY KEY,
    payment_id          BIGINT       NOT NULL REFERENCES payments(id),
    order_id            BIGINT       NOT NULL REFERENCES orders(id),
    amount              DECIMAL(12,2) NOT NULL,
    reason              VARCHAR(255),
    status              VARCHAR(30)  NOT NULL DEFAULT 'REQUESTED',
    pg_refund_key       VARCHAR(255),
    requested_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    approved_at         TIMESTAMPTZ,
    CONSTRAINT refunds_status_check CHECK (status IN (...))
);
```
