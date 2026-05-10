# OLV-002 Review (Round 2) — 체크리스트 / 검토 노트

본 노트는 칸반 본문 `## Review (Round 2)`에 들어가지 않는 세부 근거다.
Round 2의 변경 범위는 `build.gradle.kts` 단일 파일(2 곳) + 문서 1줄로 한정된다.

## 0. 변경 범위 확인

| 경로 | 변경 위치 | 변경 요지 | 마지막 수정 |
|---|---|---|---|
| `build.gradle.kts` | L27 | `extra["testcontainersVersion"]` 1.20.3 → 1.21.4 | 5월 10일 22:13 |
| `build.gradle.kts` | L62-82 | macOS `DOCKER_HOST` 자동 탐지 우선순위 정정 (`~/.docker/run/docker.sock` 우선, `docker.raw.sock`은 fallback) | 동일 |
| `docs/OLV-002/work/feature.md` | L21 | BOM 1.21.4 + 소켓 우선순위 주석 1줄 동기화 | — |

다른 모든 산출물(`docker-compose.yml` / `application*.yml` / `V1__init_baseline.sql` / `PostgresIntegrationSupport.java` / `RepositoryIntegrationTest.java` / `BootstrapTest.java` / `.gitignore`)의 modtime은 Round 1 Implementation/Review 시점 그대로 — 변경 0건. Hard rule "Touch only what the ticket requires" 준수.

## 1. 체크리스트 (Round 2)

| 항목 | 결과 | 근거 |
|---|---|---|
| 명확성 / 네이밍 | OK | `dockerHost` 로컬 val, `home` 로컬 val. 의도와 이름이 1:1 매칭. |
| 주석 품질 | OK | L64-70 주석이 (1) 왜 user socket 인지, (2) Ryuk reaper bind-mount 제약, (3) 우선순위 3단계를 명시. 누가 읽어도 의도가 살아남는다. |
| 에러 핸들링 | N/A | 빌드 스크립트 — `firstOrNull` 가 "둘 다 없음" 케이스를 null로 떨어뜨리고, 그 경우 Testcontainers 기본 탐지로 위임. 별도 예외 throw 불필요. |
| 보안 — 시크릿 | OK | 신규 시크릿 0건. `unix://` 스킴 + 사용자 홈 경로만 노출, 외부에 expose 안 함. |
| 보안 — 셸 인젝션 | OK | `File`/`absolutePath`만 사용, 문자열 concat이 아닌 `unix://` prefix + `it.absolutePath`. `user.home` 시스템 프로퍼티는 Gradle JVM이 안전한 경로로 채움. |
| 성능 | OK | `File.exists()` 호출은 Gradle config phase에 1회 — 무시 가능. 테스트 실행 자체에는 영향 없음. |
| 단순성 | OK | 9줄 인라인 — 별도 함수 추출은 과공학. 동일 로직이 다른 task에서 재사용되지 않음. |
| 죽은 코드 / 디버그 출력 | OK | `println`/`System.out` 없음. 1.20.3 잔재 없음. |
| 마이그레이션 append-only | OK | V1 미변경. |
| Hard rule — 단일 Postgres 스키마 | OK | Flyway 위치/플러그인 미변경. |
| Hard rule — 의존성 lock-in | OK | testcontainers BOM 한 줄로 docker-java/jna 등 transitive 일괄 갱신. 하위 모듈 직접 pin 회피 — 향후 Spring Boot 업그레이드 시 BOM만 따라 올라가면 된다. |

## 2. 잠재 우려 점검 (모두 해소)

| 우려 | 검토 | 결론 |
|---|---|---|
| BOM 1.21.4 가 Spring Boot 3.3.5 의 다른 의존성과 충돌? | testcontainers-bom 은 testcontainers 모듈 + docker-java family + jna 만 pin. Spring Boot/JPA 관리 버전과 비충돌. | 안전. |
| `~/.docker/run/docker.sock` 가 Linux/CI 에 없으면? | macOS 두 경로 모두 미존재 → `firstOrNull` null → `dockerHost` null → Testcontainers 기본 탐지 사용. | 크로스 플랫폼 안전. |
| docker-java 직접 pin 이 더 정밀하지 않나? | testcontainers 1.21.4 가 기대하는 docker-java 3.4.x 와 어긋나면 client/server 협상 깨짐 — BOM 단일 진입점이 옳은 입자도(granularity). | BOM-only 가 정답. |
| `tasks.withType<Test>` 가 통합 테스트 task 에도 적용되는가? | 의도된 동작. 향후 `integrationTest` task 도 동일 DOCKER_HOST 가 자동 주입. | 의도 일치. |
| `useJUnitPlatform()` 와 docker 블록이 같은 클로저에 섞여 있다 | 한 task type 의 한 설정 — 분리 시 가독성 저하. | 유지. |

## 3. CRITICAL/HIGH 발견 — 0건

Round 2 변경은 직전 QA에서 식별된 단일 dependency 호환성 결함을 해결하기 위한 최소 변경(BOM 한 줄 + 소켓 우선순위 한 블록)으로, 추가 결함이 발견되지 않았다.

## 4. NIT — 유지 결정

| 위치 | 내용 | 결정 |
|---|---|---|
| `build.gradle.kts:62` | `useJUnitPlatform()` 와 DOCKER_HOST 블록이 한 `tasks.withType<Test>` 안에 공존 | 한 task type 의 한 설정 — 분리는 과공학, 유지. |
| `build.gradle.kts:64-70` | 주석 7줄로 길지만 Ryuk 제약을 모르는 미래 독자를 살림 | 유지. |

## 5. HTTP API / 마이그레이션 변경

- HTTP API 변경 0건 — verify 폴더의 baseline/new 응답 캡처 N/A.
- 마이그레이션 변경 0건 — V1 미변경.

## 6. 다음 QA 라운드 가이드

QA 라운드는 다음 4건을 재캡처해야 한다 (Round 1 의 환경 노이즈 우회 절차는 그대로 재사용):

1. AC1 `docker compose up -d postgres` + `psql` — 결과는 Round 1 과 동일해야 함(인프라 미변경).
2. AC2 `bootRun --spring.profiles.active=local` — 동일해야 함.
3. AC3 `./gradlew flywayInfo` — 동일해야 함.
4. **AC4 `./gradlew test` (`RepositoryIntegrationTest`)** — Round 1 RED → Round 2 GREEN 으로 전환 필수. `gradle-test.log` 신규 캡처해 직전 RED 로그 위에 덮어쓰기.

전체 회귀: `./gradlew test` 9 클래스 39 케이스 0 failure 확인.
