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

**Last updated:** 2026-05-11 by OLV-021.
