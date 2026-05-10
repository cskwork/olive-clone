# commerce-backend 부트스트랩 (OLV-001)

## 무엇을 했나

빈 저장소에 Spring Boot 3.3.5 + Java 21 모듈러 모놀리식 프로젝트의 골격을
세웠다. 어떤 도메인 코드도 들어있지 않지만, **빌드되고**, **테스트가 통과**하고,
**실행하면 헬스 체크가 응답**한다.

## 사용자 시점 변화

| 항목 | 이전 | 이후 |
|---|---|---|
| `./gradlew bootRun` | 명령 자체가 없음 | 8080 포트에서 기동 |
| `curl /actuator/health` | 응답 불가 | `{"status":"UP"}` |
| `./gradlew test` | 명령 자체가 없음 | `BootstrapTest` 2개 통과 |
| `./gradlew build` | 명령 자체가 없음 | `build/libs/commerce-backend-0.1.0.jar` 생성 |

## 만든 파일 (요약)

- 빌드: `build.gradle.kts`, `settings.gradle.kts`, `gradle/wrapper/*` (Gradle 8.10.2 고정), `gradlew`/`gradlew.bat`.
- 메인: `src/main/java/com/olive/commerce/CommerceBackendApplication.java`.
- 보안: `src/main/java/com/olive/commerce/common/config/SecurityConfig.java` —
  actuator/health permitAll, 그 외 authenticated. 부트스트랩 단계에서 인증
  엔드포인트가 없어 `anyRequest().authenticated()`는 사실상 의미가 없으나,
  starter-security가 추가됐을 때 발생하는 모든 요청 401 동작을 명시적으로
  좁혀두기 위함.
- 설정: `src/main/resources/application.yml` —
  - `DataSourceAutoConfiguration` / `HibernateJpaAutoConfiguration` 제외 (DB는 OLV-002에서 도입).
  - actuator endpoint `health,info` 노출.
- 패키지: `member`, `product`, `search`, `cart`, `order`, `payment`, `inventory`,
  `promotion`, `delivery`, `review`, `admin`, `common` — 각각 `package-info.java`
  스텁(도메인 1줄 설명 + wiki 링크).
- 테스트: `src/test/java/com/olive/commerce/BootstrapTest.java` — 컨텍스트 로딩 +
  `/actuator/health` 200/UP 검증.
- 메타: `.gitignore` (빌드/IDE/시크릿 제외), `README.md` (사전 준비, 명령어, 패키지 구조, 워크플로 링크).

## 의도적으로 미포함 (후속 티켓에서 처리)

- PostgreSQL/Flyway/Testcontainers (OLV-002).
- 도메인 코드, 엔티티, REST 컨트롤러 (OLV-010 이후 도메인 티켓들).
- Lombok 의존성은 들어가 있으나 사용처는 후속 티켓에서.
- QueryDSL은 도메인 티켓에서 도입 (PRD §18, llm-wiki/00-architecture-overview.md).
