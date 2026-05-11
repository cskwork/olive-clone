# OLV-071 Explore: PG Mock Adapter — 상세 분석

## 1. 도메인 컨텍스트 (llm-wiki/70-payment-domain.md)

### 결제 상태 머신
```
READY      → REQUESTED → APPROVED
    ↓           ↓          ↓
  FAILED     CANCELED   REFUNDED
```

### 핵심 인바리언트
- **멱등성 필수**: 모든 confirm/cancel 요청은 idempotencyKey를 동반하며, 동일 키는 항상 동일 결과를 반환해야 함
- **PG callback 우선**: 클라이언트측 성공 ≠ 결제 승인. Webhook 또는 검증 API가 권위 있음
- **금액 검증**: `requested_amount == orders.final_payment_amount` 검증 필수
- **카드 번호 미저장**: payment_key, pg_provider, transaction_id만 저장

## 2. 기존 스키마 분석 (V9__payment.sql)

### payments 테이블
| 컬럼 | 타입 | 제약조건 | 설명 |
|------|------|----------|------|
| id | BIGSERIAL | PK | 자동 증가 |
| order_id | BIGINT | UNIQUE FK → orders(id) | 1:1 관계 |
| payment_key | VARCHAR(255) | NULLABLE | PG 반환 키 |
| pg_provider | VARCHAR(50) | | toss, kcp, naverpay |
| method | VARCHAR(50) | NOT NULL, CHECK | CARD, KAKAO_PAY, NAVER_PAY, TOSS_PAY, VIRTUAL_ACCOUNT |
| status | VARCHAR(30) | NOT NULL DEFAULT 'READY', CHECK | 상태 머신 |
| requested_amount | DECIMAL(12,2) | NOT NULL, >=0 | 요청 금액 |
| approved_amount | DECIMAL(12,2) | NULLABLE, >=0 | 승인 금액 |
| idempotency_key | UUID | UNIQUE | 멱등성 키 |
| requested_at | TIMESTAMPTZ | NOT NULL | 요청 시각 |
| approved_at | TIMESTAMPTZ | NULLABLE | 승인 시각 |
| failed_reason | VARCHAR(255) | NULLABLE | 실패 사유 |

### payment_transactions 테이블 (재실행 방지)
| 컬럼 | 타입 | 제약조건 | 설명 |
|------|------|----------|------|
| id | BIGSERIAL | PK | 자동 증가 |
| payment_id | BIGINT | FK CASCADE | 소스 payments.id |
| kind | VARCHAR(30) | CHECK | REQUEST, APPROVE, CANCEL, WEBHOOK, REFUND |
| pg_response_json | JSONB | | 전체 PG 응답 |
| http_status | INTEGER | CHECK (100-599) | HTTP 상태 코드 |
| idempotency_key | UUID | NULLABLE | 선택적 멱등성 키 |
| **(payment_id, kind, idempotency_key)** | | **UNIQUE** | 재실행 방지 |

## 3. 공용 패턴 분석

### ApiResponse (common/api/ApiResponse.java)
```java
public record ApiResponse<T>(
    boolean success,
    T data,
    ErrorBody error,
    PageMeta meta
) {
    static <T> ApiResponse<T> success(T data)
    static <T> ApiResponse<T> failure(ErrorBody error)
}
```

### ErrorCode (common/error/ErrorCode.java)
기존 결제 관련 코드:
- `PAYMENT_AMOUNT_MISMATCH` (UNPROCESSABLE_ENTITY)
- `IDEMPOTENCY_CONFLICT` (CONFLICT)

추가 필요:
- `PG_TIMEOUT`, `PG_FAILED`, `PG_WEBHOOK_INVALID`

## 4. PgClient 인터페이스 설계

### 메서드 명세
```java
public interface PgClient {
    // 결제 요청 (클라이언트 → PG)
    PaymentRequestResponse requestPayment(PaymentRequest request);

    // 결제 승인 (클라이언트 → PG)
    ConfirmResponse confirmPayment(ConfirmRequest request);

    // 결제 취소 (클라이언트/관리자 → PG)
    CancelResponse cancelPayment(CancelRequest request);

    // 환불 (관리자 → PG)
    RefundResponse refund(RefundRequest request);

    // 결제 검증 (재정배치: OLV-120)
    VerifyResponse verify(String paymentKey);
}
```

### Request/Response DTOs
- `PaymentRequest`: orderId, amount, orderName, customer.*
- `ConfirmRequest`: paymentKey, orderId, amount
- `ConfirmResponse`: status (APPROVED/FAILED), approvedAt, failedReason
- `PgTimeoutException`: RuntimeException 서브클래스

## 5. MockPgClient 구현 계획

### 동작 모드 (X-Mock-Pg-Behaviour 헤더)
| 헤더 값 | 동작 | 응답 |
|---------|------|------|
| (없음) | 기본 APPROVED | status=APPROVED, approved_at=now() |
| `approve` | 명시적 승인 | 기본과 동일 |
| `fail` | 실패 시뮬레이션 | status=FAILED, failed_reason=MOCK_FAIL |
| `timeout` | 타임아웃 시뮬레이션 | 6초 대기 후 PgTimeoutException 발생 |

### Webhook 시뮬레이션
`MockPgController` → `/api/_test/pg/webhook`
- 요청: `{ "paymentKey": "...", "status": "APPROVED" }`
- 동작: 실제 POST `/api/payments/webhook`으로 프록시 (OLV-073)

## 6. 환경설정

### application-local.yml 추가
```yaml
olive:
  pg:
    provider: mock  # mock | toss (future)
```

### PgClientConfig (Spring Configuration)
```java
@Configuration
public class PgClientConfig {
    @Bean
    @ConditionalOnProperty(name="olive.pg.provider", havingValue="mock")
    PgClient mockPgClient() { return new MockPgClient(); }

    // TODO OLV-080: @ConditionalOnProperty(name="olive.pg.provider", havingValue="toss")
    // PgClient tossPgClient() { return new TossPgClient(); }
}
```

## 7. 테스트 전략

### PgClientTest (단위 테스트)
- `testConfirmDefault_ReturnsApprovedIn50ms` — AC1
- `testConfirmWithFailHeader_ReturnsFailedWithMockFailReason` — AC2
- `testConfirmWithTimeoutHeader_Blocks6sThenThrowsPgTimeoutException` — AC3

### MockPgControllerTest (MVC 테스트)
- `testWebhook_ProxiesToPaymentsWebhook` — Webhook 시뮬레이션 검증

### IntegrationTest (전체 플로우)
- 결제 생성 → MockPgClient → 승인 → Webhook 수신

## 8. 의존성 체인 확인

### OLV-070 (Done)
- ✅ payments, payment_transactions, refunds 스키마 존재
- ✅ idempotency_key UNIQUE 제약 존재
- ✅ payment_transactions 재실행 방지 UNIQUE 제약 존재

### OLV-063 (Done - Order domain)
- ✅ orders 테이블 존재 (payments.order_id FK 대상)
- ✅ final_payment_amount 존재 (결제 금액 검증용)

## 9. 잠재적 위험

1. **타임아웃 테스트 속도**: 6초 대기가 테스트 시간을 늦춤 → JUnit 5의 `@Timeout`으로 보완
2. **Webhook 순환 참조**: MockPgController → PaymentsController → PgClient → MockPgClient 방지를 위해 별도 test-only 패키지 고려
3. **멱등성 검증**: MockPgClient는 idempotency_key를 검증하지 않음 (실제 PG는 검증). OLV-073에서 구현 예정

## 10. 파일 구조 예상

```
src/main/java/com/olive/commerce/payment/
├── client/
│   ├── PgClient.java                    (interface)
│   ├── MockPgClient.java                (implementation)
│   ├── dto/
│   │   ├── PaymentRequest.java
│   │   ├── ConfirmRequest.java
│   │   ├── ConfirmResponse.java
│   │   ├── CancelRequest.java
│   │   ├── CancelResponse.java
│   │   ├── RefundRequest.java
│   │   ├── RefundResponse.java
│   │   └── VerifyResponse.java
│   └── exception/
│       └── PgTimeoutException.java
├── config/
│   └── PgClientConfig.java
└── test/
    └── MockPgController.java            (@RestController, /api/_test/pg)

src/test/java/com/olive/commerce/payment/client/
├── PgClientTest.java
└── MockPgControllerTest.java
```

## 11. PRD 참조 구절

- §6.6: 결제 상태 머신 정의
- §7.7: payments/payment_transactions/refunds 테이블 스펙
- §8.4: 결제 승인 파이프라인 (8단계)
- §14.4: 결제 금액 검증, 카드 번호 미저장 정책
- §15.1: PG 장애 런북
- §20.4: 멱등성 요구사항
