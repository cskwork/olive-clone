# OLV-004 Explore — 세부 노트

## 1. PRD/위키 근거 매핑

| 산출물 | 근거 |
|---|---|
| `ApiResponse<T>` envelope | PRD §11.x 응답 패턴, 위키 `00-architecture-overview.md` (모듈러 모놀리스 단일 응답 규약) |
| `ErrorBody.code/message/path/traceId` | PRD §16.2 request audit, `llm-wiki/98-security.md:22-26` (request-id, status, latency를 감사 로그에 기록) |
| `ErrorCode` 7종 시드 | 티켓 §Scope (MEMBER_NOT_FOUND, PRODUCT_SOLD_OUT, COUPON_INVALID, INSUFFICIENT_INVENTORY, PAYMENT_AMOUNT_MISMATCH, IDEMPOTENCY_CONFLICT, VALIDATION_FAILED, INTERNAL_ERROR) |
| `BusinessException → HTTP status` 매핑 | 티켓 §Scope ("400/404/409/422 by ErrorCode.httpStatus") |
| `RequestIdFilter` MDC | PRD §16.2 + 티켓 §Hints ("clear MDC in filter `finally` to avoid thread reuse leak") |
| `AuditLogger` 카테고리 | `llm-wiki/98-security.md:22-26` (login 실패, admin 변경, payment, inventory, coupon use) |
| Logback `JsonLayout` (logstash-encoder) | 티켓 §Hints ("OLV-130 prometheus stack scrapes this format") |

## 2. 기존 프로젝트 상태(OLV-001/002 인계 자산)

- `com.olive.commerce` 패키지 트리: 도메인별 `package-info.java` 12개 + `common/config/SecurityConfig.java`(STATELESS, `/actuator/health|info` permitAll, 그 외 authenticated) + `CommerceBackendApplication`(@SpringBootApplication).
- 의존성 (`build.gradle.kts:30-49`): web/security/validation/actuator/jpa/flyway/lombok/junit/spring-security-test/testcontainers — **`logstash-logback-encoder`가 없음** → 이번 티켓에서 추가.
- 통합 테스트 베이스 `PostgresIntegrationSupport`(@Testcontainers, @ServiceConnection PG16) — `BootstrapTest`가 이를 상속.
- `application.yml`은 actuator만 노출, logging/MDC pattern 미설정. logback 구성 파일 부재 → 새 `logback-spring.xml`로 두 종류 appender(콘솔: traceId 포함 패턴, 파일: 일자 롤링 JSON audit) 도입.

## 3. 후속 티켓 의존성 시야

- OLV-010+ 모든 도메인 컨트롤러가 `ApiResponse<T>` 반환 — record + `success(...)`/`error(...)` 정적 팩토리가 필수.
- OLV-011+ 통합 테스트가 traceId/X-Request-Id 라운드트립을 검증 — 필터를 SecurityFilterChain 전(`OncePerRequestFilter` 기본 위치)에 배치.
- OLV-130(observability)이 audit JSON 포맷을 그대로 스크레이프 — logstash-encoder의 `LogstashEncoder` (단일 라인 JSON) 채택.
- OLV-100(payment)·OLV-030(inventory)·OLV-050(coupon) 모두 audit 카테고리 1순위 사용처 — `AuditLogger`는 인터페이스 + Logback impl로 구현, 정적 헬퍼 금지(테스트에서 mock 가능해야 함).

## 4. 위험과 완화

- **R1: MDC 누수** — virtual thread / Tomcat 풀 재사용 시 X-Request-Id가 다음 요청에 새어나간다. `RequestIdFilter.doFilterInternal`의 try/finally로 항상 `MDC.remove(...)` (티켓 §Hints 명시).
- **R2: ErrorBody 직렬화 안정성** — Jackson이 record를 쓰려면 Spring Boot 3.x 기본 모듈로 충분(추가 모듈 불필요). 다만 nullable `data`/`error`/`meta`는 envelope 레벨에서 `null` 허용 — `@JsonInclude(Include.NON_NULL)` 으로 비어있는 필드 생략.
- **R3: Logback file appender 권한/경로** — `log/audit-YYYY-MM-DD.log` 상대 경로는 프로세스 CWD 기반. 통합 테스트가 매번 다른 CWD를 가질 수 있으므로 `${LOG_PATH:-log}` SpEL 화 — application.yml에서 `logging.file.path` 또는 system property로 오버라이드 가능.
- **R4: GlobalExceptionHandler가 Spring Security 401/403을 가로채면 안 됨** — `@ExceptionHandler(Exception.class)`는 `AuthenticationException`/`AccessDeniedException`을 제외하거나 별도 처리(여기서는 fallthrough 허용해 SecurityFilterChain의 EntryPoint가 처리).
- **R5: Logback 자동 발견** — `logback-spring.xml`은 Spring Boot 부트 시 자동 로드. 그러나 `logstash-logback-encoder`가 classpath에 없으면 부팅 실패 → build.gradle.kts에 추가가 선결.

## 5. 첫 실패 테스트(작성 순서)

1. `GlobalExceptionHandlerTest` — `@WebMvcTest(controllers = TestController.class)` + 임시 컨트롤러 3개(BusinessException 던지기 / @Valid 실패 / 일반 RuntimeException). MockMvc로 status code + body envelope 검증. **현재 `BusinessException`/`@RestControllerAdvice` 부재로 컴파일 실패 → RED.**
2. `RequestIdFilterTest` — `MockMvc` 하나의 GET 요청 → 응답 헤더 `X-Request-Id` 존재 + UUIDv4 형식, 로그 캡처(Slf4j Logger Captor)에 동일 traceId 출현.
3. `AuditLoggerJsonIT` — `@SpringBootTest` 환경, 임시 디렉토리에 `LOG_PATH` 지정, `auditLogger.log("LOGIN_SUCCESS", Map.of("memberId", 42))` 호출 후 `log/audit-YYYY-MM-DD.log` 파일에 한 줄 JSON이 들어가고 필수 키(`event`, `memberId`, `traceId`, `@timestamp`)가 존재.

## 6. 산출물 디렉토리(이번 티켓)

```
src/main/java/com/olive/commerce/common/
  api/ApiResponse.java                 # record envelope
  api/PageMeta.java                    # record (page,size,total)
  error/ErrorBody.java                 # record (code,message,path,traceId)
  error/ErrorCode.java                 # enum (httpStatus)
  error/BusinessException.java         # RuntimeException
  error/GlobalExceptionHandler.java    # @RestControllerAdvice
  web/RequestIdFilter.java             # OncePerRequestFilter
  audit/AuditLogger.java               # interface
  audit/LogbackAuditLogger.java        # Logback-backed impl
src/main/resources/
  logback-spring.xml                   # 콘솔 + audit JSON appender
src/test/java/com/olive/commerce/common/
  error/GlobalExceptionHandlerTest.java
  web/RequestIdFilterTest.java
  audit/LogbackAuditLoggerIT.java
```
