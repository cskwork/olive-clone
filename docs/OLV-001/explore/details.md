# OLV-001 Explore — 세부

## 1. 도메인 맥락 (PRD §5.1, llm-wiki/00-architecture-overview.md)

- 모듈러 모놀리식 단일 Gradle 루트, 단일 Spring Boot 애플리케이션, 단일 Postgres 스키마.
- 도메인 패키지 12개: `member`, `product`, `search`, `cart`, `order`, `payment`, `inventory`, `promotion`, `delivery`, `review`, `admin`, `common` — 패키지 경계로 도메인 격리, 추후 §21.1 시나리오에서 search/product/order만 분리 가능하도록 설계.
- 크로스 도메인 호출은 항상 `ApplicationEventPublisher` + outbox(96-eventing.md) — 직접 repository 호출 금지. OLV-001에서는 패키지 스캐폴딩만 만들고 이벤트 인프라는 후속 티켓.
- 잠금된 기술 스택 (PRD §18, llm-wiki/00):
  - Java 21, Spring Boot 3.x, Gradle (Kotlin DSL), Spring Security, Spring Data JPA, QueryDSL.
  - Postgres + Flyway, Redis, OpenSearch, S3/LocalStack — 모두 OLV-002 이후에 도입.
  - 테스트: JUnit 5, Spring Boot Test, Testcontainers, MockMvc, REST Assured, k6.

## 2. 후속 티켓의 요구 (kanban/OLV-002.md, OLV-003.md…)

- OLV-002: `spring.autoconfigure.exclude=DataSourceAutoConfiguration`을 제거하고 Postgres+Flyway를 활성화. 따라서 OLV-001의 `application.yml`은 “DB 비활성화” 상태가 명시적이어야 OLV-002가 깔끔히 한 줄을 지움.
- OLV-002에서 `RepositoryIntegrationTest`(Testcontainers)를 추가하므로 Gradle 의존성에는 spring-data-jpa는 들어가지만 driver는 빠진다 (postgresql JDBC는 OLV-002).
- 후속 도메인 티켓들은 모두 `com.olive.commerce.<domain>` 하위 패키지에 코드를 추가 — Spring `@SpringBootApplication`이 기본 base package인 main 클래스 위치는 `com.olive.commerce`여야 모든 도메인이 자동 스캔된다.

## 3. 환경 사전 조사

- Host에는 JDK 17 (Microsoft, `/usr/libexec/java_home`)만 있고 Gradle 미설치.
- 작업: `brew install openjdk@21 gradle`로 JDK 21 + Gradle 9.x 설치 후 `gradle wrapper` 명령으로 wrapper 생성. AC가 “Gradle 8.x 이상”이므로 9.5는 OK.

## 4. Spring Initializr-equivalent 의존성 체크리스트 (티켓 Hints)

| 의존성 | Gradle 좌표 | OLV-001 사용 |
|---|---|---|
| Spring Web | `org.springframework.boot:spring-boot-starter-web` | actuator/health + bootRun |
| Spring Data JPA | `…spring-boot-starter-data-jpa` | OLV-002에서 활성화. 지금은 의존성만 포함하고 autoconfigure exclude로 비활성. |
| Spring Security | `…spring-boot-starter-security` | 의존성 포함 시 actuator도 자동 보호 — 부트스트랩 단계에선 health 엔드포인트가 익명 접근 가능해야 한다. 따라서 SecurityFilterChain bean으로 `/actuator/health` permitAll. |
| Validation | `…spring-boot-starter-validation` | 후속 DTO에서 사용. |
| Actuator | `…spring-boot-starter-actuator` | health 엔드포인트. |
| Lombok | `org.projectlombok:lombok` (compileOnly + annotationProcessor) | optional — Java 21 record 활용 가능하지만 entity/builder 위해 포함 권장. |
| Spring Boot Test | `…spring-boot-starter-test` | testImplementation. |

## 5. 위험 / 함정

- **Spring Security의 기본 401**: starter-security를 추가하면 모든 엔드포인트가 기본 401이 된다. AC는 `/actuator/health → {"status":"UP"}` (HTTP 200)을 요구하므로 SecurityFilterChain bean으로 명시적 permitAll 필요.
- **DataSourceAutoConfiguration exclude 위치**: `@SpringBootApplication(exclude=…)` 또는 `application.yml`의 `spring.autoconfigure.exclude` — 후자가 OLV-002에서 한 줄 삭제로 되돌리기 쉽다.
- **Gradle 버전과 Java 21 호환성**: Gradle 8.5+ 부터 Java 21 toolchain 안정 지원. brew의 9.5는 안전.
- **Wrapper jar 커밋 vs .gitignore**: `gradle/wrapper/gradle-wrapper.jar`는 반드시 저장소에 포함되어야 `./gradlew`가 동작. `.gitignore`는 `.gradle/`(캐시)만 무시.
- **Java toolchain 미선언**: Host의 기본 JDK가 17이라도 `java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }`을 선언하면 Gradle이 자동으로 toolchain JVM을 받아 사용 — Spring Boot가 21 바이트코드로 빌드된다. 이 선언을 빠뜨리면 컴파일러가 host 기본 17로 떨어져 record pattern 등 21 기능을 못 쓴다.

## 6. 최종 산출 파일 트리 (이 티켓 종료 시점)

```
.
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── gradle/wrapper/{gradle-wrapper.jar, gradle-wrapper.properties}
├── gradlew, gradlew.bat
├── .gitignore
├── README.md
└── src
    ├── main
    │   ├── java/com/olive/commerce
    │   │   ├── CommerceBackendApplication.java
    │   │   ├── package-info.java
    │   │   ├── admin/package-info.java
    │   │   ├── cart/package-info.java
    │   │   ├── common/package-info.java
    │   │   │   └── config/SecurityConfig.java   ← actuator permitAll
    │   │   ├── delivery/package-info.java
    │   │   ├── inventory/package-info.java
    │   │   ├── member/package-info.java
    │   │   ├── order/package-info.java
    │   │   ├── payment/package-info.java
    │   │   ├── product/package-info.java
    │   │   ├── promotion/package-info.java
    │   │   ├── review/package-info.java
    │   │   └── search/package-info.java
    │   └── resources/application.yml
    └── test/java/com/olive/commerce/BootstrapTest.java
```

(SecurityConfig는 `common/config/` 하위에 둔다 — 인프라성 설정은 common 패키지 내부에 모인다.)
