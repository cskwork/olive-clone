# OLV-003 Explore 세부 노트

## 1. wiki 인용 정리

- `llm-wiki/00-architecture-overview.md`(저장소 맵): Redis = cache + session + 재고 락, OpenSearch = 상품 검색 인덱스, S3(LocalStack) = 상품/리뷰 이미지. PRD §5.2.
- `llm-wiki/30-inventory-domain.md`: 재고 락 default = Redisson `RLock` (key `lock:inv:{product_option_id}`). **Redisson은 OLV-030에서 별도 도입** — 본 티켓은 RedisTemplate<String,String>까지만 노출.
- `llm-wiki/95-search-domain.md`: Phase 2에서 `productId/productName/brandName/.../status` 6개 필드 인덱스. 본 티켓은 OpenSearchClient 빈 + 헬스체크까지.
- `llm-wiki/99-failure-handling.md`: Redis 다운 시 `SELECT ... FOR UPDATE` fallback / OpenSearch 다운 시 503 응답 / 외부 의존 실패 처리 책임은 도메인 티켓.
- `llm-wiki/02-persistence-baseline.md`: Testcontainers BOM **1.21.4 고정** (도커 엔진 29 + API 1.44+ 협상). macOS 소켓 우선순위 블록 그대로 활용.

## 2. 의존 라이브러리 후보

| 통합 | 운영 라이브러리 | Testcontainers 모듈 |
|---|---|---|
| Redis | `spring-boot-starter-data-redis` (Lettuce 내장) | `org.testcontainers:testcontainers` `GenericContainer<>("redis:7-alpine")` (Testcontainers core에 redis 전용 모듈 없음 — `com.redis:testcontainers-redis`는 3rd-party 미사용 결정) |
| S3 (LocalStack) | `software.amazon.awssdk:s3` (BOM `software.amazon.awssdk:bom:2.28.x`) | `org.testcontainers:localstack` |
| OpenSearch | `org.opensearch.client:opensearch-rest-client:2.x` + `org.opensearch.client:opensearch-java:2.13.x` | `org.opensearch:opensearch-testcontainers:2.x` (또는 Testcontainers core `OpensearchContainer`) |

선택 결정:
- Redis Testcontainers: **Generic container**. 3rd-party 종속 회피 + 컨테이너가 단순(no module-specific config 필요).
- OpenSearch Testcontainers: **`org.opensearch:opensearch-testcontainers`** — `OpensearchContainer`를 제공하고 `withSecurityEnabled(false)` 등 OS-2 specific helper 포함.
- AWS SDK v2 BOM 채택 — `software.amazon.awssdk:bom`로 transitive 일치 보장.

## 3. Spring Config 구조 결정

```
src/main/java/com/olive/commerce/common/config/
├── SecurityConfig.java           (기존)
├── RedisConfig.java              (신규) — RedisTemplate<String,String>
├── AwsS3Config.java              (신규) — S3Client + AwsS3Properties
├── AwsS3Properties.java          (신규) — @ConfigurationProperties("aws.s3")
├── OpenSearchConfig.java         (신규) — OpenSearchClient + OpenSearchProperties
└── OpenSearchProperties.java     (신규) — @ConfigurationProperties("olive.opensearch")
```

`olive.opensearch.uris` 네임스페이스 채택 — 사용자 도메인 prefix(`olive.*`) 컨벤션과 일관성. (티켓 본문 `opensearch.uris`도 그대로 매핑 가능하나 `01-common-conventions.md`의 `olive.*` 네이밍 규칙에 맞춤.)

`aws.s3.*` prefix는 AWS SDK 관용 + 티켓 본문 명시이므로 그대로 사용.

## 4. Properties 매핑

`application.yml` (공통 default — endpoint 미지정으로 실제 AWS와도 호환):

```yaml
aws:
  s3:
    region: ap-northeast-2
    bucket: commerce-images-local
    # endpoint, access-key, secret-key 는 local 프로필에서만 채움
olive:
  opensearch:
    uris: http://localhost:9200
```

`application-local.yml`:

```yaml
spring:
  data:
    redis:
      host: localhost
      port: 6379
aws:
  s3:
    endpoint: http://localhost:4566
    access-key: test
    secret-key: test
```

`application-test.yml` 또는 Testcontainers `@DynamicPropertySource`로 컨테이너 동적 endpoint 주입.

## 5. docker-compose 서비스 추가 결정

```yaml
redis:
  image: redis:7-alpine
  ports: ["6379:6379"]
  healthcheck: redis-cli ping → PONG

localstack:
  image: localstack/localstack:3   # latest 대신 major 핀 (3.x 안정)
  ports: ["4566:4566"]
  environment:
    SERVICES: s3
    AWS_DEFAULT_REGION: ap-northeast-2
  volumes:
    - ./docker/localstack/init:/etc/localstack/init/ready.d:ro

opensearch:
  image: opensearchproject/opensearch:2
  environment:
    discovery.type: single-node
    DISABLE_SECURITY_PLUGIN: "true"
    OPENSEARCH_JAVA_OPTS: "-Xms512m -Xmx512m"
  ports: ["9200:9200"]
  ulimits: { memlock: { soft: -1, hard: -1 } }
```

`docker/localstack/init/01-create-bucket.sh` 신규 — `awslocal s3 mb s3://commerce-images-local`.

LocalStack `latest` 대신 **`3`**으로 메이저 핀: latest 태그가 silently 4.x로 점프하면 SDK v2 호환성 깨질 가능성 차단. (티켓은 latest를 명시했지만 동일 효과 + 안정성 우선 — Review에서 검토.)

## 6. Testcontainers 베이스 패턴

OLV-002의 `PostgresIntegrationSupport` 패턴 그대로 차용:

- `RedisIntegrationSupport` → `GenericContainer<>("redis:7-alpine").withExposedPorts(6379)` + `@DynamicPropertySource`로 `spring.data.redis.host/port` 주입
- `LocalStackIntegrationSupport` → `LocalStackContainer.withServices(S3)` + bucket 사전 생성 + `@DynamicPropertySource`로 `aws.s3.endpoint/access-key/secret-key`
- `OpenSearchIntegrationSupport` → `OpensearchContainer("opensearchproject/opensearch:2.15.0").withSecurityEnabled(false)` + `@DynamicPropertySource`로 `olive.opensearch.uris`

각 IT는 `@SpringBootTest(classes = …Config.class)` 슬라이스로 가벼운 컨텍스트만 띄움.

## 7. First failing test (TDD seed)

`RedisIntegrationTest.setAndGetRoundtrip()`:
```java
@Autowired RedisTemplate<String,String> redis;
@Test void setAndGetRoundtrip() {
  redis.opsForValue().set("olv003:smoke","ok");
  assertThat(redis.opsForValue().get("olv003:smoke")).isEqualTo("ok");
}
```

빈 자체가 없으므로 컨텍스트 로드 실패(`NoSuchBeanDefinitionException`)가 첫 RED. `RedisConfig` 추가 + Properties 주입으로 GREEN.

S3, OpenSearch는 동일 패턴 — 빈 부재 → 빈 추가.

## 8. 잠재 리스크

- **OpenSearch 메모리**: 컨테이너 default heap 1g 이상 — CI 환경에서는 `-Xms512m -Xmx512m`로 다이어트.
- **LocalStack init script**: Docker Desktop on macOS에서 ready.d hook이 안 도는 사례 보고 — fallback은 `S3Client` 부팅 시 `headBucket` 실패하면 자동 `createBucket` 한 번.
- **Spring Boot 3.3.5 + Lettuce**: client default 타임아웃 60s — connect 타임아웃을 `spring.data.redis.timeout=2s`로 명시(부팅 시 도커 미기동 환경에서도 빠르게 fail-fast).
