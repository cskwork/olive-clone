# OLV-072 Plan Candidates

## Option A: PaymentService 일체형 구현 (추천)

### 구조
```
payment/
├── Payment.java (엔티티)
├── PaymentRepository.java
├── PaymentTransaction.java (엔티티)
├── PaymentTransactionRepository.java
├── PaymentService.java
│   └── confirmPayment(orderNo, paymentKey, amount, idempotencyKey)
├── PaymentController.java
└── event/
    └── PaymentApprovedEvent.java
```

### 장점
- 단일 책임: 결제 도메인이 payment 패키지에 캡슐화됨
- OrderService와의 의존성 최소화 (order_id로만 참조)
- 기존 다른 도메인의 패턴을 따름

### 단점
- PaymentService가 여러 서비스(Inventory, Coupon, Point)를 호출해야 함
- 트랜잭션 경계 설계 필요

### 메서드 시그니처
```java
@Transactional
public ConfirmResponse confirmPayment(
    String orderNo,
    String paymentKey,
    BigDecimal amount,
    UUID idempotencyKey
)
```

---

## Option B: OrderService에 confirmPayment 추가

### 구조
```
order/
└── OrderService.java
    └── confirmPayment(orderNo, paymentKey, amount, idempotencyKey)
```

### 장점
- 주문 생성(OLV-061)과 취소(OLV-062)가 OrderService에 있는 패턴 유지
- 주문 라이프사이클 관점에서 일관성

### 단점
- OrderService가 결제 도메인 로직을 알아야 함 (관심사 분리 위반)
- Payment entity가 order 패키지 외부에서도 필요함 (PG callback 등)
- 결제 관련 테스트가 OrderService 테스트와 섞임

---

## Option C: PaymentService + PaymentOrchestrationService 분리

### 구조
```
payment/
├── domain/
│   ├── Payment.java
│   └── PaymentRepository.java
├── application/
│   └── PaymentOrchestrationService.java
│       └── confirmPayment(...) // 8단계 조율
└── client/
    └── PgClient.java (이미 존재)
```

### 장점
- DDD 패턴: 도메인 엔티티와 애플리케이션 서비스 분리
- 결제 도메인 로직이 순수하게 유지됨

### 단점
- 현재 프로젝트의 다른 도메인(OrderService, CouponService 등) 패턴과 불일치
- 과잉 설계일 수 있음 (현재 요구사항 복잡도에서는)

---

## Option D: Saga 패턴 도입

### 구조
```
payment/
└── PaymentSagaService.java
    └── 각 단계를 보상 로직과 함께 정의
```

### 장점
- 분산 트랜잭션에서 강력함
- 롤백 로직이 명확함

### 단점
- 현재 단일 DB 트랜잭션으로 충분함 (과잉)
- 기존 코드베이스 패턴과 불일치
- 복잡도 증가

---

## 추천: Option A

### 이유
1. **기존 패턴 일치**: OrderService, CouponService, PointService, InventoryService가
   모두 단일 패키지에 domain + service 구조를 따름
2. **관심사 분리**: Payment 도메인이 독립적인 패키지를 가짐
3. **적절한 복잡도**: 현재 요구사항(단일 DB, 단일 PG)에 맞는 수준의 설계
4. **확장성**: 향후 PG callback, refund 등이 추가되더라도 payment 패키지 내에서 처리 가능

### 첫 번째 실패 테스트

```java
@SpringBootTest
@AutoConfigureTestDatabase(replace = NONE)
class PaymentConfirmApiIT extends PostgresIntegrationSupport {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MockPgClient mockPgClient;

    @Test
    void happyPath_PAYMENT_PENDING_to_PAID() {
        // Given: member, product, order(PAYMENT_PENDING), payment(REQUESTED)
        Long orderId = givenOrderReadyForPayment();

        // When: POST /api/payments/confirm with valid body
        MvcResult result = mockMvc.perform(post("/api/payments/confirm")
                .header("Idempotency-Key", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "orderNo": "ORD202605100001",
                        "paymentKey": "mock-payment-key-123",
                        "amount": 35000
                    }
                    """))
            .andExpect(status().isOk())
            .andReturn();

        // Then:
        // 1. orders.status = PAID
        // 2. payments.status = APPROVED, payment_key set
        // 3. inventory reservation COMMITTED
        // 4. coupon USED
        // 5. points used + earn_scheduled
        // 6. outbox event PAYMENT_APPROVED
        verifyOrderStatus(orderId, "PAID");
        verifyPaymentStatus(orderId, "APPROVED");
        verifyInventoryCommitted(orderId);
        verifyCouponUsed(orderId);
        verifyPointsUsed(orderId);
        verifyOutboxEvent(orderId, "PAYMENT_APPROVED");
    }
}
```
