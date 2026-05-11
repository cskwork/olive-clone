# OLV-072 Recommendation

## 선택한 접근법: Option A - PaymentService 일체형 구현

### 결정 이유

1. **기존 코드베이스 패턴 일치**
   - OrderService, CouponService, PointService, InventoryService 모두
     도메인 패키지 내에 Service 클래스를 가지는 구조를 따름
   - 일관성 있는 코드 구조는 유지보수성 향상

2. **관심사 분리 (Separation of Concerns)**
   - Payment 도메인이 `com.olive.commerce.payment` 패키지에 캡슐화됨
   - OrderService는 주문 라이프사이클에만 집중
   - 결제 관련 로직이 payment 패키지로 분리됨

3. **단일 DB 트랜잭션으로 충분**
   - Step 4-7은 단일 `@Transactional`로 처리 가능
   - Step 8(outbox)은 `@TransactionalEventListener(AFTER_COMMIT)`로 분리
   - Saga나 분산 트랜잭션 패턴은 불필요한 복잡도

4. **향후 확장성**
   - PG webhook 처리 (OLV-110)
   - Refund 처리
   - 결제 재정배치 배치 (OLV-120)
   모두 payment 패키지 내에서 추가 가능

---

## 구현 파일 목록

### Domain Entities (JPA)
| 파일 | 설명 |
|------|------|
| `Payment.java` | payments 테이블 엔티티 |
| `PaymentRepository.java` | Payment JPA Repository |
| `PaymentTransaction.java` | payment_transactions 테이블 엔티티 |
| `PaymentTransactionRepository.java` | PaymentTransaction JPA Repository |

### Service & Controller
| 파일 | 설명 |
|------|------|
| `PaymentService.java` | 8단계 결제 확인 로직 |
| `PaymentController.java` | `POST /api/payments/confirm` 엔드포인트 |

### Event & DTOs
| 파일 | 설명 |
|------|------|
| `PaymentApprovedEvent.java` | Spring ApplicationEvent |
| `PaymentDtos.java` | ConfirmRequest, ConfirmResponse DTOs |

---

## 첫 번째 실패 테스트 (TDD 시작점)

```java
@Test
@DisplayName("Happy path: PAYMENT_PENDING → PAID, 모든 side effects 완료")
void happyPath_paymentPending_to_paid() throws Exception {
    // Given: 회원, 상품, 주문(PAYMENT_PENDING), 결제(REQUESTED) 생성
    Long memberId = givenMember();
    Long productId = givenProduct();
    Long orderId = givenOrderReadyForPayment(memberId, productId);
    Long paymentId = givenPaymentRequested(orderId);

    String orderNo = "ORD202605100001";
    UUID idempotencyKey = UUID.randomUUID();

    // When: POST /api/payments/confirm
    mockMvc.perform(post("/api/payments/confirm")
            .header("Idempotency-Key", idempotencyKey)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                    "orderNo": "%s",
                    "paymentKey": "mock-payment-key-123",
                    "amount": 35000
                }
                """.formatted(orderNo)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.orderNo").value(orderNo))
        .andExpect(jsonPath("$.data.status").value("PAID"));

    // Then: 모든 상태 변화 검증
    // 1. orders.status = PAID
    // 2. payments.status = APPROVED, payment_key set, approved_at set
    // 3. payment_transactions에 APPROVE 행 기록 (idempotency_key와 함께)
    // 4. inventory reservations COMMITTED
    // 5. coupon USED
    // 6. points used + earn_scheduled
    // 7. outbox event PAYMENT_APPROVED

    assertOrdersStatus(orderId, "PAID");
    assertPaymentsStatus(paymentId, "APPROVED");
    assertPaymentTransactionExists(paymentId, "APPROVE", idempotencyKey);
    assertInventoryCommitted(orderId);
    assertCouponUsed(orderId);
    assertPointsUsed(orderId);
    assertOutboxEventExists("PAYMENT_APPROVED", orderId);
}
```

---

## Idempotency 검증 테스트

```java
@Test
@DisplayName("같은 Idempotency-Key로 재요청 시 PG를 호출하지 않고 캐시된 응답 반환")
void replayWithSameIdempotencyKey_returnsCachedResponse() throws Exception {
    // Given: 결제 이미 승인됨
    Long orderId = givenOrderReadyForPayment();
    UUID idempotencyKey = UUID.randomUUID();

    // When: 첫 번째 요청
    mockMvc.perform(post("/api/payments/confirm")
            .header("Idempotency-Key", idempotencyKey)
            .contentType(MediaType.APPLICATION_JSON)
            .content(confirmJson("ORD001", "pg-key-1", 35000)))
        .andExpect(status().isOk());

    // When: 같은 idempotencyKey로 두 번째 요청
    mockMvc.perform(post("/api/payments/confirm")
            .header("Idempotency-Key", idempotencyKey)
            .contentType(MediaType.APPLICATION_JSON)
            .content(confirmJson("ORD001", "pg-key-1", 35000)))
        .andExpect(status().isOk());

    // Then: PG는 한 번만 호출됨 (MockPgClient 호출 횟수 검증)
    verify(mockPgClient, times(1)).confirmPayment(any());
}
```

---

## 다음 단계 (Implementation)

1. **Payment, PaymentTransaction 엔티티 생성**
2. **PaymentService.confirmPayment() 스켈레톤** (테스트가 실패하도록)
3. **테스트 실행 → 실패 확인**
4. **PaymentService 구현** (8단계 로직)
5. **PaymentController 구현**
6. **PaymentApprovedEvent 발행**
7. **모든 테스트 통과 확인**
