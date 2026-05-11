# OLV-003 Review 체크리스트

## 항목별 점검

| 항목 | 결과 | 비고 |
|---|---|---|
| 명확성·네이밍 | ✓ | `RedisConfig` / `AwsS3Config` / `OpenSearchConfig` 동일 패턴, properties 레코드 명시. |
| 에러 처리 | ✓ | `ensureBucket()`이 `NoSuchBucketException` → 자동 createBucket, 그 외 예외는 부팅을 막지 않고 WARN. LocalStack/AWS 양쪽 모두에서 안전. |
| 시크릿 노출 | ✓ | LocalStack 자격 `test/test`은 LocalStack 더미. yml에 운영 자격 하드코딩 없음 — accessKey 비어 있으면 `AnonymousCredentialsProvider`로 fallback (실제 AWS는 IAM/env로 SDK 자동 발견). |
| SQL injection | n/a | DB 변경 없음. |
| 성능 | ✓ | S3 부팅 시 headBucket 1회 — 무시할 수준. OpenSearch heap `-Xms512m -Xmx512m`로 CI 메모리 다이어트. |
| 단순성 | ✓ | 빈 1개씩 + properties 1개씩. 중간 추상 없음. |
| 데드 코드 | ✓ | 발견 안 됨. |
| 디버그 print | ✓ | `System.out` 없음. SLF4J `log.info`만. |
| 라이프사이클 | ✓ | `S3Client`는 `SdkAutoCloseable.close()` Spring 자동 호출. `OpenSearchClient`는 close 메서드 없음 — RestClient 보관 후 `@PreDestroy` 명시. |
| AC 매핑 | ✓ | AC1=`docker compose up -d`(5432/6379/4566/9200), AC2=3 IT(set/get·put/get·index exists), AC3=각 Config의 init 로그. |
| 의도치 않은 회귀 | ✓ | 기존 테스트(BootstrapTest, RepositoryIntegrationTest 등)는 PostgresIntegrationSupport에 의존 — 본 변경은 그 베이스에 영향 없음. |

## 수정 사항 (Review 중 발견)

| severity | file:line | fix |
|---|---|---|
| MEDIUM | `RedisIntegrationTest.java:48` 등 3곳 | `static class TestApp`에 `@Configuration` 누락 — `@SpringBootTest(classes=TestApp.class)`가 `@Import` / `@EnableConfigurationProperties` / `@ImportAutoConfiguration`을 처리하려면 holding 클래스가 `@Configuration`이어야 한다. 모든 IT의 `TestApp`에 `@Configuration` 추가하여 수정. |

CRITICAL/HIGH 0건.

## 외부에서 의존하는 보장

후속 도메인 티켓(OLV-030/-090/-095)이 본 baseline에서 가정할 수 있는 사실:

1. `RedisTemplate<String,String>` 빈이 항상 존재(이름 `stringRedisTemplate`).
2. `S3Client` + `AwsS3Properties` 빈이 항상 존재 — 도메인은 `bucket` 값을 properties에서만 읽을 것.
3. `OpenSearchClient` 빈이 항상 존재 — 인덱스 sync는 본 빈을 outbox 워커가 사용.
4. docker-compose `up -d`로 4 서비스 모두 동시 기동.
5. 부팅 시 init 로그 3줄(Redis/S3/OpenSearch)이 INFO 레벨로 찍힘 — AC3 검증 포인트.
