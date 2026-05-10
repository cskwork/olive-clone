# OLV-002 QA Round Details

## 환경

| 항목 | 값 |
|---|---|
| OS | macOS 26.4.1 (Darwin) aarch64 |
| Docker | Docker Desktop 29.1.5, Compose v5.0.1 |
| Docker daemon API | minimum 1.44 (server 강제) |
| JDK (toolchain) | OpenJDK 21.0.11 (Homebrew, `/opt/homebrew/Cellar/openjdk@21/21.0.11`) |
| JAVA_HOME (런처) | OpenJDK 17.0.18 (Microsoft) — Gradle daemon 자체 실행용 |
| 호스트 5432 점유자 | `mathitem-postgres` (`pgvector/pgvector:pg17`) — 무관한 다른 프로젝트 |
| Gradle | 8.10.2 (`gradlew`) |
| Testcontainers | 1.20.3 (build.gradle.kts 명시) |

## 우회/임시 설정 (QA 검증 전용, 산출물 미변경)

다른 프로젝트의 `mathitem-postgres`가 호스트 5432를 점유 중. 이 컨테이너를 임의 종료하면 사용자 워크플로 영향이 있어, 산출물(`docker-compose.yml`, `application-local.yml`, `build.gradle.kts`)은 그대로 두고 검증 시점에만 환경변수와 임시 override로 호스트 publish 포트를 비워있는 55432로 재배치.

- `docs/OLV-002/qa/compose-override-no-publish.yml` — AC1용. publish 제거 후 컨테이너 내부 psql로 확인.
- `docs/OLV-002/qa/compose-override-alt-port.yml` — AC2/AC3용. 호스트 55432 ↔ 컨테이너 5432 매핑.
- 환경변수: `FLYWAY_URL=jdbc:postgresql://localhost:55432/commerce`, `SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:55432/commerce`.

호스트 점유 환경이 정리되면 위 override/env는 불필요. 산출물은 5432 명시 그대로 유효.

## AC 별 상세

### AC1 — `docker compose up -d postgres` + `psql` 연결

- `docker compose config` → 5432:5432 매핑 + `pg_isready` healthcheck 정상 (compose-config.log).
- `docker compose ... up -d` → `commerce-postgres` Started → healthcheck `healthy` 통과 (compose-up.log).
- 컨테이너 내부 psql 연결: `docker exec commerce-postgres psql -U commerce -d commerce -c 'SELECT version();'` →
  ```
  PostgreSQL 16.12 on aarch64-unknown-linux-musl, ... 64-bit
  ```
  (psql-connect.log)
- 호스트 publish (55432) 응답: `nc -z localhost 55432` → `succeeded` (LISTEN 확인).
- 호스트 PATH에 psql 클라이언트 없음 → 동일 PG16 컨테이너 내부 psql로 연결 검증 (`psql connects` AC의 본질 = 클라이언트가 PG에 연결되는가).

**판정**: ✅ PASS.

### AC2 — `bootRun --spring.profiles.active=local` + Flyway 1건 적용 로그

깨끗한 볼륨 상태에서 부팅하여 첫 마이그레이션 적용을 캡처 (bootrun-fresh.log).

```
o.f.c.i.s.JdbcTableSchemaHistory   - Schema history table "public"."flyway_schema_history" does not exist yet
o.f.core.internal.command.DbValidate - Successfully validated 1 migration (execution time 00:00.008s)
o.f.c.i.s.JdbcTableSchemaHistory   - Creating Schema History table "public"."flyway_schema_history" ...
o.f.core.internal.command.DbMigrate  - Current version of schema "public": << Empty Schema >>
o.f.core.internal.command.DbMigrate  - Successfully applied 1 migration to schema "public", now at version v1 (execution time 00:00.003s)
o.s.b.w.e.tomcat.TomcatWebServer    - Tomcat started on port 8080 (http) with context path '/'
c.o.c.CommerceBackendApplication    - Started CommerceBackendApplication in 2.857 seconds (process running for 3.03)
```

AC 키워드 `Successfully applied 1 migration` 정확히 출력.

**판정**: ✅ PASS.

### AC3 — `./gradlew flywayInfo` V1 적용 표시

`flyway-info.log`:

```
+-----------+---------+---------------+------+---------------------+---------+----------+
| Category  | Version | Description   | Type | Installed On        | State   | Undoable |
+-----------+---------+---------------+------+---------------------+---------+----------+
| Versioned | 1       | init baseline | SQL  | 2026-05-10 22:02:27 | Success | No       |
+-----------+---------+---------------+------+---------------------+---------+----------+
BUILD SUCCESSFUL in 4s
```

**판정**: ✅ PASS.

### AC4 — `RepositoryIntegrationTest` (Testcontainers) 통과

`./gradlew test` 결과: 37 tests, 2 failures (`BootstrapTest.initializationError`, `RepositoryIntegrationTest.initializationError`).

testcontainers-debug.log에서 정확한 사유 캡처:

```
EnvironmentAndSystemPropertyClientProviderStrategy: failed with exception BadRequestException
(Status 400: {"message":"client version 1.32 is too old. Minimum supported API version is 1.44, please upgrade your client to a newer version"})
UnixSocketClientProviderStrategy: failed with exception InvalidConfigurationException
(Could not find unix domain socket). Root cause NoSuchFileException (/var/run/docker.sock)
DockerDesktopClientProviderStrategy: failed with exception BadRequestException
(Status 400: {"ID":"","Containers":0,...,"Labels":["com.docker.desktop.address=unix:///Users/danny/Library/Containers/com.docker.docker/Data/docker-cli.sock"],...})
```

두 소켓(`docker.raw.sock`, `.docker/run/docker.sock`) 모두 daemon `_ping`/`/info`에는 정상 응답. 그러나 `docker-java` 클라이언트 라이브러리(Testcontainers 1.20.3 종속)가 사용하는 client API 버전 **1.32**가 Docker Desktop 29 daemon이 강제하는 **minimum 1.44**보다 낮아, 클라이언트 버전 협상 단계에서 daemon이 HTTP 400으로 거부.

| 시도 | 결과 |
|---|---|
| Default (build.gradle.kts 자동 socket detection: `docker.raw.sock`) | FAIL — client 1.32 too old |
| `DOCKER_HOST=unix:///Users/danny/.docker/run/docker.sock` 명시 | FAIL — 동일 사유 |
| `TESTCONTAINERS_RYUK_DISABLED=true` + `DOCKER_API_VERSION=1.44` | FAIL — env가 docker-java client 협상에 반영되지 않음 |

→ 사용자 환경 변수만으로 우회 불가. 산출물(의존성 버전) 변경 필요.

**판정**: ❌ FAIL — In Progress로 rewind.

## 권장 fix (다음 In Progress 라운드)

`build.gradle.kts:27` 의 `testcontainersVersion`을 1.20.3 → **1.21.4**(또는 Spring Boot 3.3.5와 호환되는 최신)로 업그레이드. 1.21.x는 docker-java 3.4.x를 끌어와 client API 1.44+ 협상 가능. 이는 OLV-002 baseline의 일부(Testcontainers 도입 자체가 본 티켓 스코프)이므로 drive-by refactor 아님.

산출물 수정 후 재시도 시 동일 하네스로 동일 4건 AC를 다시 검증.
