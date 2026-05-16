# Testing Patterns

**Summary:** E2E 및 통합 테스트 작성 시 발견한 패턴과 주의사항.

**Invariants & Constraints:**

- **트랜잭션 격리 주의**: `@BeforeEach`에서 `TransactionTemplate.executeWithoutResult()`를
  사용해 데이터를 넣으면, 해당 트랜잭션 밖의 테스트 메서드에서 데이터를 조회할 수 없음.
  해결: `JdbcTemplate`을 직접 사용하여 auto-commit 없이 데이터를 조작.
- **@Async 이벤트 리스너**: `@Async` + `@TransactionalEventListener(phase=AFTER_COMMIT)`는
  테스트 환경에서 별도 스레드 풀에서 실행되므로 즉시 결과를 확인할 수 없음.
  해결: `Awaitility.await().atMost(5, TimeUnit.SECONDS).untilAsserted(...)`로 대기하거나,
  테스트 안정성을 위해 직접 DB 조작으로 우회.
- **Testcontainers 패턴**: `PostgresIntegrationSupport` 상속으로 컨테이너 재사용.
  `@DynamicPropertySource`로 포트를 Spring 설정에 연결.

**Files of interest:**

- `src/test/java/com/olive/commerce/e2e/PurchaseFlowE2ETest.java` - 12단계 구매 플로우 E2E
- `src/test/java/com/olive/commerce/.../PostgresIntegrationSupport.java` - Testcontainers 베이스

**Decision log:**

- 2026-05-13 | OLV-140 | E2E 테스트에서 트랜잭션 격리 문제 발견. `txTemplate` 대신
  `jdbcTemplate` 직접 사용으로 해결. 리뷰 자격 검증 시 `orders.status` 변경 필요 확인.
  비동기 리스너 테스트를 위한 `await` 패턴 또는 직접 INSERT 우회 패턴 확립.

---

## k6 Load Testing Patterns

**SharedArray 패턴**: VU 간 테스트 데이터를 공유할 때 `SharedArray`를 사용합니다.
일반 배열과 달리 내부적으로 락이 구현되어 있어 동시성 문제를 방지합니다.

```javascript
const testUsers = new SharedArray('testUsers', function () {
    const users = [];
    for (let i = 1; i <= 100; i++) {
        users.push({ email: `test${i}@example.com`, password: 'Test1234!' });
    }
    return users;
});

export default function () {
    const user = testUsers[__VU % testUsers.length]; // 순차적 접근 보장
    // ...
}
```

**환경변수 처리**: `__ENV` 객체로 쉘 환경변수에 접근합니다. 타겟 URL, 모드 전환 등을 런타임에 제어할 수 있습니다.

```javascript
const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const STOCK_OUT_MODE = __ENV.STOCK_OUT_MODE === 'true';
```

**handleSummary**: 테스트 종료 후 결과를 요약하고, 여러 형식으로 저장할 수 있습니다.

```javascript
export function handleSummary(data) {
    return {
        'stdout': textSummary(data),
        'summary.json': JSON.stringify(data),
        'report.txt': customReport(data),
    };
}
```

**재고 고갈 테스트 패턴**: 재고 N개인 SKU에 대해 2N개 이상의 VU를 동시에 보내면,
정확히 N건만 성공하고 나머지는 422를 반환해야 합니다. 이를 통해 동시성 제어를 검증합니다.

**Last updated:** 2026-05-13 by OLV-141.
