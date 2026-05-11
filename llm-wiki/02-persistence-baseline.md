# Persistence Baseline (Postgres + Flyway + Testcontainers)

**Summary:** OLV-002에서 깐 데이터 계층 baseline. 로컬은 docker-compose
`postgres:16-alpine`, 통합 테스트는 Testcontainers `@ServiceConnection`으로
throwaway PG. Flyway 10.x가 `classpath:db/migration` 단일 위치에서 V1+를
적용. 후속 도메인 티켓(OLV-010+)은 모두 이 베이스 위에서 V2/V3 마이그레이션을
append-only로 추가하고, Repository 통합 테스트는 `PostgresIntegrationSupport`를
상속한다.

**Invariants & Constraints:**

- **Single Postgres schema**: Flyway 위치는 `classpath:db/migration/` _하나_.
  도메인별 디렉터리 분기 금지 — 후속 V2.../V3... 모두 같은 폴더, 같은
  flyway_schema_history에 누적.
- **Append-only migrations**: 이미 main에 커밋된 Flyway 파일은 _절대_
  편집 금지. 변경이 필요하면 새 V_N+1을 추가. PRD §20 Hard rule.
- **Local 자격 = 컨테이너 자격 = Testcontainers 자격 일치**:
  `commerce / commerce / commerce` (db / user / pass). docker-compose는
  5432 publish, alt-port가 필요한 환경(포트 점유 등)은 임시
  override + 환경변수(`SPRING_DATASOURCE_URL` / `FLYWAY_URL`)로만 우회 —
  `docker-compose.yml` / `application-local.yml` 산출물 자체는 5432 명시
  유지.
- **Testcontainers BOM 호환성**: Spring Boot 3.3.5 + Docker Engine 29
  (API 1.44+ 강제) 환경에서는 BOM `1.21.4` 이상이 필요. `1.20.x`는
  docker-java `3.3.x`(client API 1.32)를 끌어와 daemon이 협상 거절.
- **macOS Docker 소켓 우선순위**: Ryuk reaper가 bind-mount할 수 있는
  표준 사용자 소켓 `~/.docker/run/docker.sock` 우선. private socket
  `~/Library/Containers/com.docker.docker/Data/docker.raw.sock`은
  bind-mount 불가 → fallback only. `build.gradle.kts`의
  `tasks.withType<Test>` 블록이 `DOCKER_HOST` env가 비어 있을 때 자동
  탐지.
- **JPA**: `open-in-view: false` (N+1 가드), `hibernate.jdbc.time_zone=UTC`
  (운영 시간대 안정성). 두 설정 모두 `application.yml`의 `spring.*`
  네임스페이스에 위치 — 사용자 네임스페이스(`olive.*`) 아래로 잘못
  배치하면 Spring Boot가 묵묵히 무시하므로 검토 시 들여쓰기 확인.
- **`baseline-on-migrate: true`**: 운영 DB로 이관 시 기존 객체 위에서
  Flyway가 baseline을 자동으로 그어주는 안전망. 신규 환경 영향 없음.

**Files of interest:**

- `docker-compose.yml` — postgres:16-alpine, 5432, named volume
  `commerce-postgres-data`, `pg_isready` healthcheck.
- `src/main/resources/db/migration/V1__init_baseline.sql` — 빈 placeholder.
  도메인 테이블은 V2+에서 추가.
- `src/main/resources/application.yml` — Flyway/JPA 공통 설정 (`spring.*`).
- `src/main/resources/application-local.yml` — 로컬 JDBC URL/유저/비밀번호.
- `src/test/java/.../common/persistence/PostgresIntegrationSupport.java` —
  `@Testcontainers` + `@ServiceConnection` PG 컨테이너 베이스. 모든
  Repository 통합 테스트가 상속.
- `src/test/java/.../common/persistence/RepositoryIntegrationTest.java` —
  Flyway baseline 적용 + PG dialect 활성 검증 (smoke).
- `build.gradle.kts:27` — `extra["testcontainersVersion"] = "1.21.4"` BOM
  pin. 다운그레이드 금지.
- `build.gradle.kts:62-82` — macOS `DOCKER_HOST` 자동 탐지 블록.
- `build.gradle.kts:84-89` — Flyway Gradle plugin URL/credential
  (env override 우선, docker-compose default fallback).

**Decision log:**

- 2026-05-10 | OLV-002 | docker-compose `postgres:16-alpine` (5432, named
  volume, healthcheck), `application-local.yml` 분리, V1 placeholder. JPA
  자동설정 두 줄(`DataSourceAutoConfiguration`/`HibernateJpaAutoConfiguration`)
  exclude 제거.
- 2026-05-10 | OLV-002 | Flyway plugin 10.20.1 + `flyway-database-postgresql`
  (Flyway 10.x는 PG dialect를 별도 모듈로 분리 — 이걸 빠뜨리면 부팅 시
  `Unsupported Database: PostgreSQL`). buildscript classpath와
  implementation 양쪽에 모두 명시.
- 2026-05-10 | OLV-002 | Testcontainers BOM **1.21.4** 채택. 직전
  라운드에서 `1.20.3`은 Docker Engine 29 daemon(API 1.44+)에 대해
  client API 1.32가 거절당해 모든 `@ServiceConnection` 통합 테스트가
  부팅 단계에서 실패. 1.21.4가 docker-java 3.4.x를 끌어오면서 정상 협상
  + Ryuk reaper bind-mount 가능. **OLV-010+는 이 BOM 미만으로 다운그레이드
  금지.**
- 2026-05-10 | OLV-002 | `PostgresIntegrationSupport`(추상 베이스) 패턴
  채택 — 컨테이너 한 번 띄우고 Spring Boot 컨텍스트 캐시로 모든 Repository
  IT가 공유. Testcontainers `withReuse`보다 단순하고 결과는 동일.
- 2026-05-10 | OLV-002 | `application-local.yml`을 공식 로컬 프로파일로
  격상 — `.gitignore`의 무시 패턴을 제거하고, 개인 시크릿 오버레이는
  `application-local-personal.yml`로 이름 분리.

**Last updated:** 2026-05-10 by OLV-002.
