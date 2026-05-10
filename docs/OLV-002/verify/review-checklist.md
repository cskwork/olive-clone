# OLV-002 Review — 체크리스트 / 검토 노트

본 노트는 칸반 본문(`## Review`)에 들어가지 않는 세부 근거와 체크리스트 매핑이다.

## 1. 체크리스트

| 항목 | 결과 | 근거 |
|---|---|---|
| 명확성 / 네이밍 | OK | `PostgresIntegrationSupport`, `RepositoryIntegrationTest`, `V1__init_baseline.sql` — 의도와 이름이 1:1 매칭. |
| 에러 핸들링 | N/A | 인프라 baseline, 비즈니스 로직 0줄. 부팅 단계 실패는 Flyway/Hibernate 자체 메시지로 충분. |
| 보안 — 시크릿 | OK | `commerce/commerce/commerce`는 로컬 dev default. `flyway` Gradle 블록은 `FLYWAY_USER`/`FLYWAY_PASSWORD` env override 우선. JWT 키는 OLV-005에서 분리 관리, `.gitignore`에 등록됨. |
| 보안 — SQL injection | OK | V1은 `SELECT 1` placeholder, 테스트는 hardcode native query만. |
| 보안 — 시크릿 노출(application-local.yml) | OK | docker-compose가 검증한 로컬 전용 default 값. 개인 시크릿은 `application-local-personal.yml`(.gitignore)로 분리. |
| 성능 | OK | `open-in-view: false` (수정 후) — N+1 가드 활성. JPA `hibernate.jdbc.time_zone=UTC` 명시. |
| 단순성 | OK | 베이스라인 범위 내, 신규 파일 5개·수정 파일 3개로 최소 합. |
| 죽은 코드 / 디버그 출력 | OK | `System.out`/`println` 없음. 모든 로그는 SLF4J 경로. |
| 마이그레이션 append-only | OK | V1만 신규, 수정 대상 없음. |
| Hard rule — 단일 Postgres 스키마 | OK | `spring.flyway.locations=classpath:db/migration` 단일 위치. |

## 2. CRITICAL — application.yml 들여쓰기 결함

### 발견

Implementation 단계의 `application.yml`이 다음과 같이 작성되어 있었다:

```yaml
spring:
  application:
    name: commerce-backend
  autoconfigure:
    exclude:
      - org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration

olive:
  security:
    jwt:
      ...
  jpa:                          # ← olive.jpa.*  (의도: spring.jpa.*)
    open-in-view: false
    properties:
      hibernate:
        jdbc:
          time_zone: UTC
  flyway:                       # ← olive.flyway.*  (의도: spring.flyway.*)
    enabled: true
    locations: classpath:db/migration
    baseline-on-migrate: true
```

`jpa:`/`flyway:` 키가 `spring:` 한 단계 아래가 아니라 사용자 네임스페이스 `olive:` 한 단계 아래에 들어갔다. Spring Boot의
`@ConfigurationProperties` 바인딩은 `olive.jpa.*`/`olive.flyway.*`를 인식할 prefix가 없어 묵묵히 무시한다.

### 영향

- `spring.jpa.open-in-view`가 default(`true`)로 동작 → OSIV가 켜진 채 OLV-010+ 도메인 코드가 트랜잭션 밖에서도 lazy load 가능 → N+1을 못 잡고 지나친다.
- `spring.flyway.baseline-on-migrate`가 적용되지 않음 → 빈 DB에서는 Flyway가 자체 처리하지만, 운영 이관 시 기존 스키마 위에 V1을 얹는 시나리오에서 baseline 자동 처리 누락.
- `hibernate.jdbc.time_zone=UTC`가 적용되지 않아 TZ-naive `timestamp` 저장 시 default 시스템 TZ 사용 → 환경별 값 차이 위험.
- 다행히 Spring Boot 기본 Flyway location도 `classpath:db/migration`이므로 V1 자동 발견은 유지 — AC #2는 우연히 통과한다. 그러나 의도가 적용되지 않는 상태이므로 baseline 단계에서 반드시 수정.

### 수정

`jpa:`/`flyway:` 블록을 `olive:` 위로 끌어올려 `spring:` 자식이 되도록 들여쓰기 정정. 시각적 분리를 위해 `olive:` 블록은 그대로 별도 탑레벨 유지.

수정 후 (현재 파일 상태):

```yaml
spring:
  application: { name: commerce-backend }
  autoconfigure: { exclude: [ ...UserDetailsServiceAutoConfiguration ] }
  jpa:
    open-in-view: false
    properties: { hibernate: { jdbc: { time_zone: UTC } } }
  flyway:
    enabled: true
    locations: classpath:db/migration
    baseline-on-migrate: true

olive:
  security: { jwt: { ... } }
```

## 3. LOW / NIT — 유지 결정

| severity | 위치 | 내용 | 결정 |
|---|---|---|---|
| LOW | `application-local.yml:6` | `driver-class-name: org.postgresql.Driver` — JDBC URL에서 자동 추론되어 redundant. | 명시 유지(가독성). |
| LOW | `application.yml:18` | `spring.flyway.locations`가 Spring Boot 기본값과 동일해 redundant. | 의도 명시 + 후속 티켓이 안전하게 참고할 명세이므로 유지. |
| NIT | `BootstrapTest.java` | `@SpringBootTest` 클래스 명이 `*Test`로 끝남(`*IT`이 통합 테스트 컨벤션). | OLV-001 결정 — 본 티켓 범위 외, 변경 안 함. |
| NIT | `PostgresIntegrationSupport.java` | "단일 컨테이너 인스턴스 공유"는 Testcontainers `withReuse`가 아닌 Spring `@DataJpaTest` 컨텍스트 캐시 + JUnit static lifecycle로 달성. | 동일 베이스를 상속하는 한 효과는 같음 — 코드 그대로 유지. |

## 4. 추가 변경 사항 없음

- HTTP API 변경 0건 — `verify/` 의 baseline/new HTTP 응답 캡처는 본 티켓에 해당 없음.
- 마이그레이션 변경 0건(V1 신규만, 수정 대상 없음).
