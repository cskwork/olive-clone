# 권장 계획: 단일 E2E 테스트 클래스

## 선택: 옵션 1 - 단일 테스트 클래스

### 이유

1. **티켓 요구사항 충족**: 명세서에 "단일 `PurchaseFlowE2ETest`"라고 명시
2. **기존 패턴 일관성**: `OrderCreationApiIT`, `PaymentConfirmApiIT`와 동일한 구조
3. **컨텍스트 로딩 효율**: 3개 테스트가 하나의 `@SpringBootTest` 컨텍스트 공유

### 첫 번째 실패 테스트 (Red-Green-Refactor 시작점)

```java
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class PurchaseFlowE2ETest extends PostgresIntegrationSupport {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(
        DockerImageName.parse("redis:7-alpine")
    ).withExposedPorts(6379);

    @Container
    static final GenericContainer<?> OPENSEARCH = new GenericContainer<>(
        DockerImageName.parse("opensearchproject/opensearch:2.13.0")
    ).withExposedPorts(9200)
     .withEnv("discovery.type", "single-node")
     .withEnv("DISABLE_SECURITY_PLUGIN", "true");

    @Container
    static final LocalStackContainer LOCALSTACK = new LocalStackContainer(
        DockerImageName.parse("localstack/localstack:3.8")
    ).withServices(Service.S3);

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("opensearch.uris", () -> "http://" + OPENSEARCH.getHost() +
            ":" + OPENSEARCH.getMappedPort(9200));
        registry.add("spring.cloud.aws.s3.endpoint", LOCALSTACK::getEndpoint);
    }

    @Test
    @DisplayName("AC1: 회원가입~리뷰까지 전체 플로우 <60초")
    void happyPath_completePurchaseFlow() throws Exception {
        // 1. POST signup + login → bearer token
        // 2. Admin: create brand → category → product (2 options) → upload image → restock 100 each
        // 3. User: GET /api/products → find product. GET detail
        // 4. POST /api/cart/items (option A, qty 2)
        // 5. POST /api/orders with cart items + couponId + usePointAmount
        // 6. POST /api/payments/confirm with X-Mock-Pg-Behaviour: approve
        // 7. Wait up to 5s for outbox PAYMENT_APPROVED → DONE
        // 8. Assert: order PAID, inventory committed (-2), points USE, coupon USED
        // 9. POST webhook DELIVERY_COMPLETED
        // 10. Assert: order DELIVERED, points EARN spendable, review-eligible
        // 11. POST review
        // 12. Assert: product_review_summaries.review_count = 1

        fail("Not yet implemented");
    }
}
```

### 구현 순서

1. **기본 설정**: Testcontainers 4개 (Postgres, Redis, OpenSearch, LocalStack)
2. **Step 1-2**: 회원가입/로그인 + Admin 상품 생성 (helper 메서드 추출)
3. **Step 3-5**: 장바구니/주문 생성
4. **Step 6-8**: 결제 승인 + outbox 대기 + 상태 검증
5. **Step 9-10**: 배송 완료 처리 (Mock CarrierClient or 직접 호출)
6. **Step 11-12**: 리뷰 작성 + 집계 검증
7. **AC2**: 멱등성 테스트 (Step 6 재요청)
8. **AC3**: 실패 분기 테스트 (`X-Mock-Pg-Behaviour: fail`)

### 아티팩트 저장 위치

```
docs/OLV-140/qa/
├── flow.har              # HTTP recorder 출력 (옵션 - MockMvc로 대체 가능)
└── final-state.json      # DB 상태 덤프
```
