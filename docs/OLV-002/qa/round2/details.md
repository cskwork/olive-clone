# OLV-002 QA Evidence (Round 2)

직전 라운드(Round 1)에서 AC4가 RED였던 사유 — Testcontainers 1.20.3이 끌어오는 docker-java 3.3.x(API 1.32) ↔ Docker Engine 29 daemon(API 1.44+ 강제)의 client version 협상 실패 — 를 Round 2 In Progress 단계의 BOM 1.21.4 업그레이드로 해결한 뒤 다시 4건 AC를 모두 실제 명령으로 캡처한 결과를 모아둔다.

## 환경

| 항목 | 값 |
|---|---|
| Docker Engine | server / client API `1.52` (Engine 29 baseline ≥ 1.44 강제) |
| Java toolchain | `/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home` (21.0.11) |
| Gradle | 8.10.2 (single-use daemon, `JAVA_HOME` + `-Dorg.gradle.java.installations.paths` 명시) |
| Testcontainers BOM | `1.21.4` (Round 2 변경 후) |
| 호스트 5432 점유 | 무관한 `mathitem-postgres` (`pgvector/pgvector:pg17`) — 환경 노이즈 |
| 호스트 PATH `psql` | 부재 — 환경 노이즈, `docker exec` 우회 |

산출물 자체(`docker-compose.yml` / `application-local.yml`)는 5432를 명시한 채로 유지하고, QA 검증을 위해서만 임시 override(`compose-override-alt-port.yml`, host 55432 publish)와 환경변수(`SPRING_DATASOURCE_URL` / `FLYWAY_URL`)로 alt-port를 가리켰다.

## 명령 / 결과 / 핵심 출력

### AC1 — `docker compose up -d postgres` + psql 접속

```
docker compose -f docker-compose.yml \
               -f docs/OLV-002/qa/compose-override-alt-port.yml \
               up -d postgres
docker exec commerce-postgres psql -U commerce -d commerce -c "SELECT version();"
```

출력 (`psql-connect.log`):

```
PostgreSQL 16.12 on aarch64-unknown-linux-musl, compiled by gcc (Alpine 15.2.0) 15.2.0, 64-bit
```

판정 ✅ — postgres:16-alpine 컨테이너가 alt-port로 publish 되었고, fresh volume(테이블 0건) 상태에서 commerce/commerce/commerce 자격으로 정상 접속.

### AC2 — `bootRun --spring.profiles.active=local`

```
SPRING_DATASOURCE_URL='jdbc:postgresql://localhost:55432/commerce' \
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
./gradlew bootRun --args='--spring.profiles.active=local' \
  -Dorg.gradle.java.installations.paths=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
```

핵심 라인 (`bootrun.log`):

```
o.f.core.internal.command.DbValidate  - Successfully validated 1 migration (execution time 00:00.010s)
o.f.c.i.s.JdbcTableSchemaHistory      - Creating Schema History table "public"."flyway_schema_history" ...
o.f.core.internal.command.DbMigrate   - Migrating schema "public" to version "1 - init baseline"
o.f.core.internal.command.DbMigrate   - Successfully applied 1 migration to schema "public", now at version v1 (execution time 00:00.003s)
o.s.b.w.e.tomcat.TomcatWebServer      - Tomcat started on port 8080 (http) with context path '/'
c.o.c.CommerceBackendApplication      - Started CommerceBackendApplication in 3.134 seconds (process running for 3.402)
```

판정 ✅ — fresh volume 위에서 Flyway가 V1을 처음 적용 + Hibernate validate 정상 + Tomcat 8080 listen + 3.134초 만에 정상 기동. JPA 자동설정 활성 상태에서 DataSource/Hibernate가 모두 초기화됨.

### AC3 — `./gradlew flywayInfo`

```
FLYWAY_URL='jdbc:postgresql://localhost:55432/commerce' \
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
./gradlew flywayInfo \
  -Dorg.gradle.java.installations.paths=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
```

출력 (`flyway-info.log`):

```
Schema version: 1
+-----------+---------+---------------+------+---------------------+---------+----------+
| Category  | Version | Description   | Type | Installed On        | State   | Undoable |
+-----------+---------+---------------+------+---------------------+---------+----------+
| Versioned | 1       | init baseline | SQL  | 2026-05-10 22:29:09 | Success | No       |
+-----------+---------+---------------+------+---------------------+---------+----------+

BUILD SUCCESSFUL in 1s
```

판정 ✅ — Gradle Flyway plugin 10.20.1이 동일 환경변수로 가리킨 PG에서 `V1__init_baseline`을 `Success` 상태로 표시. AC2의 bootRun이 적용한 Schema History를 그대로 읽음.

### AC4 — `./gradlew test --tests RepositoryIntegrationTest`

```
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
./gradlew test \
  --tests com.olive.commerce.common.persistence.RepositoryIntegrationTest \
  --info \
  -Dorg.gradle.java.installations.paths=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
```

핵심 라인 (`gradle-test-repo.log`):

```
RyukResourceReaper  - Ryuk started - will monitor and terminate Testcontainers containers on JVM exit
DockerClientFactory - ✔︎ Docker server version should be at least 1.6.0
tc.postgres:16-alpine - Container postgres:16-alpine is starting: 674e78d2920e9958e4093e342e24a181cbf33ca035d8584d8081ba0fccb4b85a
tc.postgres:16-alpine - Container postgres:16-alpine started in PT1.04861S
tc.postgres:16-alpine - Container is started (JDBC URL: jdbc:postgresql://localhost:62715/commerce?loggerLevel=OFF)
o.f.core.internal.command.DbMigrate - Successfully applied 1 migration to schema "public", now at version v1 (execution time 00:00.004s)
RepositoryIntegrationTest > flywayBaselineIsApplied() STANDARD_OUT
    Hibernate: SELECT COUNT(*) FROM flyway_schema_history WHERE success = true
RepositoryIntegrationTest > postgresDialectIsActive() STANDARD_OUT
    Hibernate: SELECT version()
BUILD SUCCESSFUL in 5s
```

판정 ✅ — 직전 라운드 RED 사유였던 docker-java client version 협상이 1.21.4 BOM에서 정상 통과(`Container started in PT1.04861S`). `@ServiceConnection` 주입된 throwaway PG에 V1 적용 + 두 테스트 케이스(`flywayBaselineIsApplied`, `postgresDialectIsActive`) 모두 PASS.

## 회귀 검사 — 전체 테스트

```
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
./gradlew test -Dorg.gradle.java.installations.paths=...
```

`gradle-test.log` → `BUILD SUCCESSFUL in 12s`.

`build/test-results/test/*.xml` 9건 집계:

| 테스트 클래스 | tests | failures | errors |
|---|---|---|---|
| `BootstrapTest` | 2 | 0 | 0 |
| `LogbackAuditLoggerIT` | 1 | 0 | 0 |
| `JwtTokenProviderTest` | 6 | 0 | 0 |
| `AcceptanceCriteriaCapture` | 4 | 0 | 0 |
| `RequestIdFilterTest` | 4 | 0 | 0 |
| `SecurityFilterChainIT` | 9 | 0 | 0 |
| `GlobalExceptionHandlerTest` | 5 | 0 | 0 |
| `SecurityFilterChainEvidenceCapture` | 6 | 0 | 0 |
| `RepositoryIntegrationTest` | 2 | 0 | 0 |
| **합계** | **39** | **0** | **0** |

판정 ✅ — Round 2 변경(BOM 1.21.4 + 소켓 우선순위)이 다른 8개 테스트 클래스에 회귀를 만들지 않음. Spring Boot 컨텍스트 캐시가 `@ServiceConnection` PG 컨테이너를 재사용하므로 두 번째 클래스부터 컨테이너 부팅 비용 0.

## 환경 노이즈 (산출물 결함 아님)

직전 라운드와 동일하게 두 가지 환경 노이즈를 우회로만 처리했고, 산출물은 그대로 유지.

- 호스트 5432 점유: 무관한 `mathitem-postgres` 컨테이너(`pgvector/pgvector:pg17`)가 publish 중. `docs/OLV-002/qa/compose-override-alt-port.yml` 임시 override로 host 55432 publish, 환경변수로 가리킴. `docker-compose.yml` 자체는 5432 명시 그대로.
- 호스트 PATH `psql` 부재: `which psql → psql not found` (`psql-host.log`). 동일 PG16 컨테이너 내부 `docker exec ... psql`로 검증해 산출물 결함과 분리.

## 증거 파일

`docs/OLV-002/qa/round2/` 안에 모두 보존:

- `compose-config.log` · `compose-up.log` · `psql-connect.log` · `psql-host.log`
- `bootrun.log` (54 라인, fresh volume에서 Flyway V1 + Tomcat 8080 + Spring Boot started)
- `flyway-info.log` (V1 Success row 1건)
- `gradle-test-repo.log` (RepositoryIntegrationTest 단독 — Round 2 핵심 회귀 위치)
- `gradle-test.log` (전체 9 클래스 39 케이스 회귀 검사)

직전 라운드 증거(`docs/OLV-002/qa/*.log`)는 RED 시점 비교용으로 보존.

## 종합 판정

| AC | 라운드 | 결과 |
|---|---|---|
| AC1 | Round 2 | ✅ |
| AC2 | Round 2 | ✅ |
| AC3 | Round 2 | ✅ |
| AC4 | Round 2 | ✅ (Round 1 RED → BOM 1.21.4 업그레이드로 GREEN) |

**판정**: ✅ PASS — 4건 모두 GREEN. state `QA → Learn`.
