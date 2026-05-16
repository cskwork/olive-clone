# Infra Baseline (Redis + S3/LocalStack + OpenSearch)

**Summary:** OLV-003에서 깐 캐시·이미지·검색 인프라 baseline. 로컬은 docker-compose
4 서비스(postgres + redis + localstack + opensearch). Spring `common.config`
패키지에 `RedisTemplate<String,String>`(Spring 자동설정 `StringRedisTemplate`이
서브클래스로 만족) / `S3Client`(AWS SDK v2) / `OpenSearchClient`
(legacy `RestClient` transport) 빈을 한 개씩 노출. 각 빈은 Testcontainers
통합 테스트가 SET/GET·PUT/GET·index exists 라운드트립으로 자체 검증.
OLV-030(재고 락) / OLV-090(이미지 업로드) / OLV-095(검색)은 본 빈을
`@Autowired` 한 줄로 주입받아 시작.

**Invariants & Constraints:**

- **Redis 빈은 자동설정 위임**: 우리 `RedisConfig`는 init 로그만 남기고
  빈 정의는 하지 않는다. `RedisAutoConfiguration.stringRedisTemplate`
  (`StringRedisTemplate extends RedisTemplate<String, String>`)이
  `@Autowired RedisTemplate<String,String>` 매칭을 그대로 만족.
  **새로 빈을 정의하면 메서드명 충돌(`stringRedisTemplate`)로
  `BeanDefinitionOverrideException`** — OLV-003 1차 라운드 실패 사례.
- **운영 모드 S3 부팅에서 외부 호출 금지**: `aws.s3.endpoint`가 비어 있으면
  (=실제 AWS) `ensureBucket` warm-up 자체를 스킵. 운영에서 부팅 시점에
  AWS API 호출은 권한·네트워크 미스 시 부팅 실패의 시한폭탄. devops가
  버킷을 외부에서 만든다는 정책을 코드로 못 박음(`AwsS3Config:31-34`).
  endpoint 명시(=LocalStack)일 때만 LocalStack init script 실패 안전망으로
  headBucket → createBucket 시도, 실패도 WARN 로그로 흡수.
- **LocalStack은 path-style 강제**: `S3Configuration.pathStyleAccessEnabled(true)`
  필수 — virtual-hosted style을 LocalStack이 지원하지 않는다(`AwsS3Config:29`).
  실제 AWS도 path-style 호환이므로 운영에도 그대로.
- **AWS 자격 fallback**: `aws.s3.access-key`가 비어 있으면
  `AnonymousCredentialsProvider`. 실제 운영은 IAM 인스턴스 프로파일 또는
  환경변수에서 SDK가 자동 발견 — yml에 시크릿 하드코딩 금지.
- **OpenSearch transport 선택**: `opensearch-rest-client` (legacy Apache
  HttpClient 4) + `RestClientTransport`(opensearch-java 2.13.x)로 묶음.
  `ApacheHttpClient5TransportBuilder`도 가능하나 baseline은 안정성 우선.
  `RestClient`는 `Closeable` — `OpenSearchConfig`가 보관 후 `@PreDestroy`로
  닫는다(`OpenSearchConfig:25-39`).
- **Testcontainers 모듈**:
  - Redis = `GenericContainer("redis:7-alpine").withExposedPorts(6379)`
    (Testcontainers core에 redis 전용 모듈 없음, 3rd-party 회피).
  - LocalStack = `org.testcontainers:localstack`,
    `LocalStackContainer.withServices(Service.S3)`.
  - OpenSearch = `org.opensearch:opensearch-testcontainers:2.1.3`
    (OpenSearch 프로젝트 공식, security off helper 포함).
- **테스트 컨텍스트 슬라이스 패턴**: 각 IT는 `@SpringBootTest(classes =
  TestApp.class)` + `@Configuration` + `@ImportAutoConfiguration` /
  `@EnableConfigurationProperties` / `@Import`로 좁은 컨텍스트만 띄움
  (JPA/PG 끌고 들어오지 않음). **TestApp inner 클래스에 `@Configuration`
  필수** — 누락 시 `@Import`/`@EnableConfigurationProperties` 메타 처리
  안 됨.
- **컴포지션 정책**: 도메인 코드는 본 빈을 1차로만 사용한다. OLV-030의
  Redisson `RLock`은 본 `RedisConnectionFactory` 위에 별도 빈을 쌓고,
  OLV-095의 인덱서 워커는 본 `OpenSearchClient`를 outbox 드레이너에서만
  호출(쓰기 패스에 직접 두지 않음 — 95 wiki §Invariants 참조).

**Files of interest:**

- `docker-compose.yml` — postgres / redis / localstack(SERVICES=s3) /
  opensearch(single-node, DISABLE_SECURITY_PLUGIN=true) 4 서비스 + healthcheck.
- `docker/localstack/init/01-create-bucket.sh` — `awslocal s3 mb
  commerce-images-local` (best-effort, 실패 무시).
- `src/main/resources/application.yml` — `aws.s3.region/bucket` default,
  `olive.opensearch.uris` default.
- `src/main/resources/application-local.yml` — `spring.data.redis.host/port`,
  `aws.s3.endpoint/access-key/secret-key=test/test`.
- `src/main/java/.../common/config/RedisConfig.java` — init 로그만(빈 정의 X).
- `src/main/java/.../common/config/AwsS3Properties.java` +
  `AwsS3Config.java` — `@ConfigurationProperties("aws.s3")` 레코드 +
  `S3Client` 빈(localMode 가드 + path-style + Anonymous fallback).
- `src/main/java/.../common/config/OpenSearchProperties.java` +
  `OpenSearchConfig.java` — `@ConfigurationProperties("olive.opensearch")` +
  `OpenSearchClient` 빈(legacy RestClient + JacksonJsonpMapper) +
  `@PreDestroy`.
- `src/test/java/.../common/config/RedisIntegrationTest.java`,
  `AwsS3IntegrationTest.java`, `OpenSearchIntegrationTest.java` — 각 1
  케이스(set/get·put/get·index exists).
- `build.gradle.kts:27-29` — `awsSdkVersion=2.28.29`, `opensearchClientVersion=2.13.0`,
  Testcontainers BOM 1.21.4 유지.
- `build.gradle.kts:31-58` — starter-data-redis / awssdk:s3 /
  opensearch-rest-client + opensearch-java / testcontainers:localstack /
  opensearch:opensearch-testcontainers:2.1.3.

**Decision log:**

- 2026-05-10 | OLV-003 | docker-compose에 redis/localstack/opensearch 추가.
  LocalStack `:3` 메이저 핀(latest 회피), opensearch는 2 메이저 + heap
  512m 다이어트 + named volume.
- 2026-05-10 | OLV-003 | Spring 자동설정 `StringRedisTemplate`을 그대로
  쓰기로 결정 — 메서드명 충돌 회피 + 코드 단순화. RedisConfig는 init log
  전용. 1차 라운드 RED(BeanDefinitionOverrideException, 18건 회귀)에서
  학습.
- 2026-05-10 | OLV-003 | S3 bucket warm-up은 LocalStack mode 한정.
  운영 AWS는 devops가 버킷 외부 생성. 1차 라운드에서 익명 자격
  + 운영 endpoint로 부팅 시 403 — endpoint 가드 + 안쪽 RuntimeException
  catch 추가로 해결.
- 2026-05-10 | OLV-003 | OpenSearch transport는 legacy `RestClient` +
  `RestClientTransport` 채택. ApacheHttpClient5TransportBuilder는 가능하나
  baseline은 검증된 경로 우선.
- 2026-05-10 | OLV-003 | Testcontainers OpenSearch 모듈은
  `org.opensearch:opensearch-testcontainers:2.1.3`(OpenSearch 공식)
  채택. Testcontainers core는 1.21.4에서 opensearch 모듈 미포함.

- 2026-05-10 | OLV-003 | Testcontainers OpenSearch 모듈은
  `org.opensearch:opensearch-testcontainers:2.1.3`(OpenSearch 공식)
  채택. Testcontainers core는 1.21.4에서 opensearch 모듈 미포함.
- 2026-05-11 | OLV-021 | Gradle 자동 JDK 다운로드 설정: `gradle.properties`에
  `org.gradle.java.installations.auto-download=true` 추가로 시스템에 Java 21이
  없어도 Gradle 8+가 자동으로 다운로드. CI/CD 및 로컬 개발 환경 설정 간소화.

**Last updated:** 2026-05-13 by OLV-130.

---

## Observability (OLV-130)

**Summary:** Prometheus + Grafana 모니터링 스택. Spring Boot Actuator + Micrometer로
메트릭을 `/actuator/prometheus`에 노출하며, Prometheus가 이를 스크래핑하여
Grafana 대시보드에 시각화한다.

**Invariants & Constraints:**

- **Prometheus endpoint**: `/actuator/prometheus`는 `management.endpoints.web.exposure.include`
  설정으로만 활성화됨. Micrometer Prometheus 의존성이 필요함.
- **도메인 메트릭 네이밍**: `commerce_*` 접두사를 사용하여 내장 메트릭과 구분.
- **메트릭 태그**: 동적 태그(status, pg_provider, optionId)는 `MeterRegistry.counter()`를
  직접 호출하여 처리. `Counter.builder().register()`는 매번 새 인스턴스를 생성하므로
  비효율적.
- **이벤트 리스너 방식**: 도메인 이벤트(OrderCreatedEvent, PaymentApprovedEvent 등)를
  수신하여 메트릭을 기록. 도메인 코드와 메트릭 수집 로직 분리.
- **HikariCP 메트릭**: Spring Boot 3.x는 `hikaricp_connections_*`를 자동으로 노출하므로
  별도 Gauge 불필요.
- **Grafana 프로비저닝**: `infra/grafana/provisioning/datasources/`와
  `provisioning/dashboards/` YAML 파일로 컨테이너 시작 시 자동 설정. 수동 설정 불필요.
- **Alert 규칙**: `infra/prometheus/alerts.yml`에 Prometheus 규칙 정의.
  PRD §16.3 예시(결제 실패율 10% 이상 알림) 구현됨.

**Files of interest:**

- `build.gradle.kts:41` — `micrometer-registry-prometheus` 의존성.
- `src/main/resources/application.yml:29-44` — `management.endpoints.*` 설정.
- `src/main/java/.../common/metrics/CommerceMetrics.java` — 커스텀 메트릭 정의.
- `src/main/java/.../common/metrics/MetricsRecorder.java` — 이벤트 리스너로 메트릭 기록.
- `src/main/java/.../search/SearchMetrics.java` — 검색 메트릭 헬퍼.
- `Dockerfile` — 멀티 스테이지 빌드 (jdk21-alpine → jre21-alpine).
- `docker-compose.yml` — prometheus + grafana 서비스 추가.
- `infra/prometheus/prometheus.yml` — 스크래핑 타겟 설정.
- `infra/prometheus/alerts.yml` — 알림 규칙 (PRD §16.3).
- `infra/grafana/provisioning/datasources/prometheus.yml` — Prometheus datasource.
- `infra/grafana/provisioning/dashboards/dashboards.yml` — 대시보드 자동 로드.
- `infra/grafana/dashboards/commerce-backend.json` — 사전 정의된 대시보드.

**Decision log:**

- 2026-05-13 | OLV-130 | Prometheus + Grafana 채택. Spring Boot Actuator와 완벽 통합되며
  docker-compose로 로컬 개발 환경 재현 가능. OpenTelemetry는 설정 복잡.
- 2026-05-13 | OLV-130 | 이벤트 리스너로 메트릭 기록. 도메인 코드와 결합도 최소화.
  실패 시 메트릭 소실 가능성 있으나 메트릭은 best-effort로 수락.

---

## Load Testing (OLV-141)

**Summary:** k6를 사용한 부하 테스트 스크립트 모음. 상품 목록 조회와 주문 생성 플로우에 대한
베이스라인 성능과 재고 초과 판매 방지를 검증한다.

**Invariants & Constraints:**

- **k6 설치 필요**: `brew install k6` 또는 https://k6.io/docs/getting-started/installation/
- **타겟 환경**: docker-compose로 실행 중인 로컬 서비스 (postgres, redis, localstack, opensearch)
- **결과 저장**: `docs/OLV-141/qa/` 하위에 JSON과 텍스트 요약이 자동 저장됨
- **Golden Signals**: 지연 시간(http_req_duration), 트래픽(http_reqs), 오류(http_req_failed),
  포화(Prometheus/Grafana에서 확인) 캡처
- **임계값**: product-list는 p95 < 300ms, order-create는 p95 < 1s (PRD §16.3)
- **재고 고갈 테스트**: 재고 10개인 SKU에 50 VU 동시 주문 시, 정확히 10건만 성공해야 함

**Files of interest:**

- `infra/k6/product-list.js` — 상품 목록 부하 테스트 (50 VU, 2분, p95 < 300ms)
- `infra/k6/order-create.js` — 주문 생성 부하 테스트 (20 VU, 2분) + 재고 고갈 모드 (50 VU, 30초)
- `infra/k6/lib/config.js` — 공통 설정 (BASE_URL, 정렬 옵션)
- `infra/k6/lib/auth.js` — 인증 헬퍼 (login, Bearer 토큰)
- `infra/k6/runload.sh` — 실행 래퍼 (서비스 확인, 결과 저장)
- `infra/k6/README.md` — 사용 문서

**Decision log:**

- 2026-05-13 | OLV-141 | k6 채택. Go 기반 단일 바이너리, JavaScript 스크립트로 작성, Grafana와 연동 용이.
- 2026-05-13 | OLV-141 | SharedArray로 VU 간 테스트 데이터 공유. 순차적 접근 보장으로 동시성 문제 방지.
- 2026-05-13 | OLV-141 | handleSummary로 결과 요약. JSON과 텍스트 형식으로 저장, CI/CD 파이프라인에 활용 가능.

**Last updated:** 2026-05-13 by OLV-141.
