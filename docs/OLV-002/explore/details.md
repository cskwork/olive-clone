# OLV-002 Explore — 세부

본 노트는 칸반 본문에 들어가지 않는 세부 인용·검증을 모은다.

## 1. 베이스라인 (OLV-001 산출물)

- `build.gradle.kts:20-32` — `spring-boot-starter-data-jpa`/`spring-boot-starter-security`가 의존성에 들어가 있고 자동설정만 꺼져 있다. 따라서 OLV-002는 (1) Postgres 드라이버 추가, (2) Flyway 추가, (3) `application.yml` exclude 두 줄 제거만 하면 자동으로 활성화된다.
- `src/main/resources/application.yml:1-15` — `DataSourceAutoConfiguration` + `HibernateJpaAutoConfiguration` exclude. 본 티켓에서 이 두 줄만 제거(세 번째 `UserDetailsServiceAutoConfiguration`은 OLV-010까지 유지).
- `src/main/java/com/olive/commerce/CommerceBackendApplication.java` — `@SpringBootApplication`이 `com.olive.commerce` 루트에 있어 Spring Boot 자동스캔이 모든 도메인 패키지의 `@Entity`/`@Repository`를 발견 가능. JPA 활성 시 별도 `@EntityScan`/`@EnableJpaRepositories` 필요 없음.
- `src/test/java/com/olive/commerce/BootstrapTest.java` — `@SpringBootTest`로 컨텍스트 로딩 검증. JPA 활성 후에도 통과해야 한다 (회귀 가드).

## 2. 외부 환경

- `docker --version` → 29.1.5, `docker compose version` → v5.0.1 — Docker Compose v2 형식의 `docker-compose.yml` 사용 가능.
- `psql` 호스트 미설치 — AC #1 (`psql connects`)은 컨테이너 내부 `psql -U commerce`로 검증.
- `git`은 본 작업트리에서 미초기화(`fatal: not a git repository`) — 변경사항은 파일 시스템 기준으로 검증, git history는 사용 불가.

## 3. 의사결정에 영향을 주는 wiki 인용

- `llm-wiki/00-architecture-overview.md:11` — "One Gradle root, one Spring Boot application, **one Postgres schema**." → 도메인별 스키마 분리를 도입하지 않음. Flyway 위치도 단일 `db/migration/`.
- `llm-wiki/00-architecture-overview.md:42-50` — Decision log에서 OLV-002의 책임을 명시: "앞 둘은 OLV-002에서 Postgres 도입 시 제거".
- `llm-wiki/99-failure-handling.md:28-33` — Redis outage runbook에서 inventory lock fallback이 `SELECT ... FOR UPDATE`로 명시 → 로컬 Postgres가 항상 떠 있어야 fallback 검증 가능.
- 티켓 §Hints — `spring.flyway.locations=classpath:db/migration` 지정. Spring Boot 3.x 자동 활성: classpath에 `flyway-core` + `org.flywaydb.flyway-core-postgresql` 존재 시.

## 4. Spring Boot 3.x Flyway / Testcontainers 관련 사실

- Spring Boot 3.3.5 부터 Postgres용 별도 모듈이 필요: `org.flywaydb:flyway-database-postgresql` (Flyway 10.x 분리). `flyway-core`만으로는 PG dialect 미지원.
- `@ServiceConnection`(`spring-boot-testcontainers`)으로 Testcontainers 연결을 zero-config로 wiring. `@DataJpaTest` + `@Testcontainers` + `PostgreSQLContainer`에 `@ServiceConnection` 부착하면 `spring.datasource.*` 자동 설정.
- `@DataJpaTest`는 기본적으로 in-memory H2를 띄우려 하므로 `@AutoConfigureTestDatabase(replace = NONE)` 필요.

## 5. 위험 분석

- **R1**: `flyway-core`만 추가하고 `flyway-database-postgresql`을 빠뜨리면 부팅 시 `Unsupported Database: PostgreSQL` 실패. → 두 의존성 모두 명시.
- **R2**: `application.yml`이 단일 파일이면 `local`/`test` 프로파일이 한 데 섞여 production 시 Testcontainers JDBC URL이 우선될 수 있음. → `application.yml`(공통) + `application-local.yml`(로컬 DB) 분리, test에는 별도 `application-test.yml`(profile-specific) 또는 `@ServiceConnection`으로 동적 주입.
- **R3**: Flyway `V1__init_baseline.sql`이 빈 파일이면 Flyway 10.x가 `MigrationVersionMissing` 또는 SQL 파싱 오류를 낼 수 있음. → 최소 한 줄 SQL 주석을 두어 안전 (PG는 `-- ...` 주석만 있어도 OK).
- **R4**: 테스트 시 host에서 Docker가 꺼져 있으면 Testcontainers 시작 자체가 실패. QA 단계에서 미리 Docker 데몬 상태 확인.
- **R5**: `bootRun --args='--spring.profiles.active=local'`이 `application-local.yml` 없을 때 default(test connection 없음)로 떨어져 실패. → 반드시 local 프로파일용 yml 생성.

## 6. AC vs 작업 매핑

| AC | 산출물/명령 |
|---|---|
| `docker compose up -d postgres` + psql 연결 | `docker-compose.yml`, `docker compose exec postgres psql -U commerce` |
| `bootRun --args=local`이 Flyway 1건 적용 | `application-local.yml`, `V1__init_baseline.sql`, JPA exclude 제거 |
| `./gradlew flywayInfo` | `org.flywaydb.flyway` Gradle plugin + `flyway { url=… }` 블록 |
| `RepositoryIntegrationTest` (Testcontainers) | `spring-boot-testcontainers`, `org.testcontainers:postgresql`, `@ServiceConnection`, `@DataJpaTest` |
