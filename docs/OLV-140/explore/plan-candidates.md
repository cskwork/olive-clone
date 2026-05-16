# 계획 옵션 분석

## 옵션 1: 단일 테스트 클래스 - 순차 실행

**구조:**
```
PurchaseFlowE2ETest.java
  ├─ happyPath_test()                     # AC1
  ├─ idempotencyReplay_test()             # AC2
  └─ paymentFailureNegative_test()        # AC3
```

**장점:**
- 코드 중복 최소화 (setUp/tearDown 공유)
- `@SpringBootTest` 컨텍스트 로딩 비용 절감
- 테스트 간 데이터 격리 명확

**단점:**
- 테스트 순서 의존성 없어야 함 (각각 독립적 데이터 생성)

**구현 복잡도:** 낮음

---

## 옵션 2: Scenario별 테스트 클래스 분리

**구조:**
```
e2e/
  ├─ PurchaseFlowHappyPathE2ETest.java
  ├─ PurchaseFlowIdempotencyE2ETest.java
  └─ PurchaseFlowFailureE2ETest.java
```

**장점:**
- 각 시나리오 독립적 실행 가능
- 실패 시 빠른 원인 파악

**단점:**
- 코드 중복 (각 클래스별 Testcontainers 설정)
- 컨텍스트 로딩 비용 증가

**구현 복잡도:** 중간

---

## 옵션 3: Given-When-Then 스타일의 유창한 API

**구조:**
```
PurchaseFlowTest.java
  └─ PurchaseFlowScenario
       ├─ givenMember()      // 회원 생성
       ├─ andLoggedIn()      // 로그인
       ├─ andProduct()       // 상품 생성
       ├─ whenCartAdd()      // 장바구니 추가
       ├─ whenOrder()        // 주문 생성
       ├─ whenPayment()      // 결제 승인
       └─ thenOrderPaid()    // 주문 Paid 상태 검증
```

**장점:**
- 가독성 최고 (비-개발자도 이해 가능)
- 테스트 코드가 문서처럼 읽힘

**단점:**
- 빌더 패턴 구현 비용
- Spring 통합 테스트와 어울리지 않음 (MockMvc와 궁합)

**구현 복잡도:** 높음

---

## 권장 옵션: 옵션 1 (단일 테스트 클래스)

**이유:**
1. 티켓 요구사항이 "단일 PurchaseFlowE2ETest"임
2. AC가 3개로 적어서 코드 중복보다 공유 설정의 이득이 큼
3. 기존 IT 패턴(`OrderCreationApiIT`, `PaymentConfirmApiIT`)과 일관성

**구현 스케치:**
```java
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class PurchaseFlowE2ETest extends PostgresIntegrationSupport {

    @Container static RedisContainer REDIS = ...
    @Container static OpenSearchContainer OPENSEARCH = ...
    @Container static LocalStackContainer LOCALSTACK = ...

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        // Redis, OpenSearch, S3 포트 바인딩
    }

    @Test
    @DisplayName("AC1: 회원가입~리뷰까지 전체 플로우 <60초")
    void happyPath_completePurchaseFlow() { }

    @Test
    @DisplayName("AC2: 동일 Idempotency-Key 재요청 시 동일 상태")
    void idempotencyReplay_sameKey_returnsIdenticalState() { }

    @Test
    @DisplayName("AC3: PG fail 시 PAYMENT_PENDING, 예약 유지")
    void paymentFailure_negativeBranch() { }
}
```
