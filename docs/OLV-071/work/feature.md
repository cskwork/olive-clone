# OLV-071: Mock PG Adapter — 기능 설명

## 개요

OLV-071은 결제 도메인의 PG(Payment Gateway) 인터페이스와 Mock 구현을 제공합니다. QA에서 결제 성공/실패/타임아웃 케이스를 재시작 없이 테스트할 수 있으며, 실제 PG 연동 시 한 클래스만 교체하면 됩니다.

## 주요 컴포넌트

### 1. PgClient 인터페이스
`payment/client/PgClient.java`

PG와의 통신을 추상화하는 인터페이스입니다. 다음 5개 메서드를 제공합니다:

- `requestPayment(PaymentRequest)`: 결제 요청 (paymentKey 발급)
- `confirmPayment(ConfirmRequest)`: 결제 승인
- `cancelPayment(CancelRequest)`: 결제 취소
- `refund(RefundRequest)`: 환불
- `verify(String)`: 결제 검증 (재정배치용)

### 2. MockPgClient 구현
`payment/client/MockPgClient.java`

PgClient 인터페이스의 인메모리 Mock 구현입니다. 동작 모드를 제어하여 QA에서 다양한 케이스를 테스트할 수 있습니다:

| 동작 모드 | 설정 방법 | 동작 |
|-----------|----------|------|
| 기본(APPROVE) | behaviour = null 또는 "approve" | APPROVED 반환 |
| 실패 | behaviour = "fail" | FAILED 반환 (failedReason=MOCK_FAIL) |
| 타임아웃 | behaviour = "timeout" | 6초 대기 후 PgTimeoutException 발생 |

### 3. MockPgController
`payment/test/MockPgController.java`

QA용 테스트 엔드포인트를 제공합니다:

- `POST /api/_test/pg/webhook`: PG 웹훅 시뮬레이션
- `POST /api/_test/pg/behaviour`: 동작 모드 설정

### 4. DTOs
`payment/client/dto/*.java`

Java 21 record를 사용한 불변 DTO들:

- `PaymentRequest`, `PaymentRequestResponse`: 결제 요청/응답
- `ConfirmRequest`, `ConfirmResponse`: 결제 승인 요청/응답
- `CancelRequest`, `CancelResponse`: 결제 취소 요청/응답
- `RefundRequest`, `RefundResponse`: 환불 요청/응답
- `VerifyResponse`: 결제 검증 응답

### 5. PgClientConfig
`payment/config/PgClientConfig.java`

Spring 설정 클래스입니다. `olive.pg.provider` 프로퍼티에 따라 MockPgClient를 빈으로 등록합니다:

```java
@Bean
@ConditionalOnProperty(name = "olive.pg.provider", havingValue = "mock")
public PgClient mockPgClient() {
    return new MockPgClient();
}
```

### 6. PgTimeoutException
`payment/client/exception/PgTimeoutException.java`

PG 타임아웃 예외입니다. RuntimeException을 상속하며, 타임아웃 케이스를 테스트할 때 사용됩니다.

## 환경설정

### application-local.yml
```yaml
olive:
  pg:
    provider: mock
```

이 설정으로 MockPgClient가 활성화됩니다.

## 사용 예

### 기본 결제 승인 테스트
```java
PgClient pgClient = new MockPgClient();
ConfirmRequest request = new ConfirmRequest(
    "payment-key", 1001L, new BigDecimal("15000"), UUID.randomUUID()
);
ConfirmResponse response = pgClient.confirmPayment(request);
// response.status() == "APPROVED"
```

### 실패 케이스 테스트
```java
MockPgClient mockClient = new MockPgClient();
mockClient.setBehaviour("fail");
ConfirmResponse response = mockClient.confirmPayment(request);
// response.status() == "FAILED"
// response.failedReason() == "MOCK_FAIL"
```

### 타임아웃 케이스 테스트
```java
MockPgClient mockClient = new MockPgClient();
mockClient.setBehaviour("timeout");
assertThatThrownBy(() -> mockClient.confirmPayment(request))
    .isInstanceOf(PgTimeoutException.class);
```

## 향후 작업

- **OLV-080**: 실제 PG(Toss) 연동 시 `TossPgClient` 구현 및 `PgClientConfig`에 빈 추가
- **OLV-073**: 실제 웹훅 엔드포인트 구현 (`POST /api/payments/webhook`)
