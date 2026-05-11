# OLV-003 — 로컬 인프라 (Redis / S3 LocalStack / OpenSearch) feature 문서

## 무엇이 추가되었나

기존에는 docker-compose에 Postgres 한 대만 떠 있었고, Spring config에는
DB·Security·Flyway만 있었다. OLV-003은 후속 도메인 티켓이 의존하는 3대
인프라(Redis 캐시·락 / S3 이미지 업로드 / OpenSearch 검색)를 모두 한 번에
깔고, Spring `common.config` 패키지에 한 줄 `@Autowired`로 주입 가능한 빈
3개를 등록한다.

## 사용 방법

### 로컬에서 인프라 띄우기

```bash
docker compose up -d
# 4개 컨테이너가 모두 healthy까지 약 30초 (OpenSearch가 가장 늦다)
docker compose ps
```

* postgres : `localhost:5432` (commerce/commerce/commerce)
* redis    : `localhost:6379` (no auth)
* localstack : `localhost:4566` (S3 only, 버킷 `commerce-images-local` 자동 생성)
* opensearch : `localhost:9200` (보안 플러그인 비활성)

### 도메인 코드에서 사용하기

```java
// Redis
@Autowired RedisTemplate<String, String> redisTemplate;
redisTemplate.opsForValue().set("key", "value");

// S3 (AWS SDK v2)
@Autowired S3Client s3Client;
@Autowired AwsS3Properties s3Props;            // bucket 이름 등
s3Client.putObject(PutObjectRequest.builder()
    .bucket(s3Props.bucket()).key("a/b.png").build(), RequestBody.fromBytes(bytes));

// OpenSearch
@Autowired OpenSearchClient openSearchClient;
openSearchClient.indices().exists(ExistsRequest.of(b -> b.index("products")));
```

OLV-030(재고 락)은 위 `RedisTemplate`을 다시 감싸 Redisson `RLock`으로
승격할 예정이지만, 캐시·세션 용도는 모두 이 빈을 직접 사용한다.

## Spring 프로필별 설정

| key | application.yml (default) | application-local.yml |
|---|---|---|
| `spring.data.redis.host/port` | (Spring Boot default) | `localhost / 6379` |
| `aws.s3.endpoint` | (없음 — 실제 AWS) | `http://localhost:4566` |
| `aws.s3.region` | `ap-northeast-2` | (상속) |
| `aws.s3.bucket` | `commerce-images-local` | (상속) |
| `aws.s3.access-key/secret-key` | (없음 — IAM 또는 환경변수) | `test / test` |
| `olive.opensearch.uris` | `http://localhost:9200` | (상속) |

테스트 프로필(`test`)은 `@DynamicPropertySource`로 Testcontainers 컨테이너의
mapped port를 직접 주입한다 — yml 변경 불필요.

## docker-compose 변경 요약

```diff
+ redis (redis:7-alpine, 6379, healthcheck=PING)
+ localstack (localstack/localstack:3, 4566, SERVICES=s3, init script로 버킷 생성)
+ opensearch (opensearchproject/opensearch:2, 9200, single-node, 보안 플러그인 OFF)
+ named volume `commerce-opensearch-data`
+ docker/localstack/init/01-create-bucket.sh — awslocal s3 mb
```

## 새 의존성

* `org.springframework.boot:spring-boot-starter-data-redis` (Lettuce 내장)
* `software.amazon.awssdk:s3` (BOM `software.amazon.awssdk:bom:2.28.29`)
* `org.opensearch.client:opensearch-rest-client:2.13.0`
* `org.opensearch.client:opensearch-java:2.13.0`
* `org.testcontainers:localstack` (test)
* `org.opensearch:opensearch-testcontainers:2.1.3` (test)

## Hard rule 준수

* docker-compose는 **append-only**로 추가 — postgres 블록 그대로 유지.
* Flyway 마이그레이션 변경 없음 — 본 티켓은 스키마 무관.
* 시크릿 하드코딩 없음 — LocalStack 자격(`test/test`)은 LocalStack 내부 더미.
