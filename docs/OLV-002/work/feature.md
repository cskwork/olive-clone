# OLV-002 — PostgreSQL + Flyway + docker-compose baseline

## 사용자가 보는 변화

- **로컬 개발자**: `docker compose up -d postgres` → 5432에 commerce DB 기동, 그대로 `./gradlew bootRun --args='--spring.profiles.active=local'` 가능. 기동 로그에 `Successfully applied 1 migration to schema "public"` 출력.
- **CI / 통합 테스트**: `./gradlew test` 실행 시 Testcontainers가 throwaway PG를 한 번 기동, 모든 통합 테스트가 동일 컨테이너 컨텍스트를 캐시 공유 — 추가 설정 0개.
- **DBA / 마이그레이션 운영자**: `./gradlew flywayInfo` 명령으로 V1__init_baseline 적용 상태를 단일 명령으로 조회.

## 새 파일

| 경로 | 책임 |
|---|---|
| `docker-compose.yml` | 로컬 Postgres 16-alpine. `commerce/commerce/commerce`, 5432, named volume + healthcheck. |
| `src/main/resources/application-local.yml` | 로컬 프로파일 — JDBC URL/사용자/비밀번호 + Hibernate `validate` + PG dialect. docker-compose 와 1:1 매칭. |
| `src/main/resources/db/migration/V1__init_baseline.sql` | Flyway 베이스라인 placeholder. 도메인 테이블은 V2+에서 추가 (Hard rule: applied 마이그레이션은 수정 금지). |
| `src/test/java/com/olive/commerce/common/persistence/PostgresIntegrationSupport.java` | 추상 베이스 — `@Testcontainers` + `@ActiveProfiles("test")` + `PostgreSQLContainer`(`@ServiceConnection`). 모든 통합 테스트가 단일 컨테이너 인스턴스 공유. |
| `src/test/java/com/olive/commerce/common/persistence/RepositoryIntegrationTest.java` | 신규 AC 검증 — PG dialect 활성 / Flyway baseline 적용 확인. |

## 변경 파일

- `build.gradle.kts` — Flyway core/postgres + Postgres JDBC 드라이버 + Testcontainers BOM(1.21.4) + spring-boot-testcontainers + junit-jupiter + postgresql 모듈 추가. `org.flywaydb.flyway` Gradle plugin(10.20.1) 활성, `flyway { url/user/password/locations }` 블록 추가(env 변수 우선, 기본값은 docker-compose). `buildscript`에 `flyway-database-postgresql` classpath 추가 — Flyway 10.x가 dialect 모듈을 분리 요구. macOS `DOCKER_HOST` 자동 탐지는 표준 사용자 소켓(`~/.docker/run/docker.sock`)을 우선, 구형 Docker Desktop fallback으로 `docker.raw.sock`을 후순위 — Ryuk reaper가 bind-mount할 수 있는 경로를 우선 선택.
- `src/main/resources/application.yml` — JPA 자동설정 exclude 두 줄(`DataSourceAutoConfiguration` / `HibernateJpaAutoConfiguration`) 제거. `spring.flyway.locations=classpath:db/migration` + `baseline-on-migrate: true` 추가, JPA `open-in-view: false` + UTC 시간대 명시.
- `.gitignore` — `application-local.yml`이 무시 패턴이었으나, OLV-002에서 docker-compose 짝꿍의 공식 로컬 프로파일로 격상 — 체크인 가능하도록 제거하고 개인 시크릿 오버레이는 `application-local-personal.yml`로 분리(주석 명시).
- `src/test/java/com/olive/commerce/BootstrapTest.java` — JPA 활성화 후 datasource 빈 필요 — `PostgresIntegrationSupport` 상속으로 동일 Testcontainers PG 사용.

## 따라가는 명령

```bash
docker compose up -d postgres
./gradlew flywayInfo
./gradlew bootRun --args='--spring.profiles.active=local'
./gradlew test
```
