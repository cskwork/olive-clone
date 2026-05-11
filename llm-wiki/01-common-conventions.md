# Common Conventions (API Envelope / Errors / TraceId / Audit)

**Summary:** `com.olive.commerce.common` 의 횡단 인프라 (PRD §11 응답 패턴, §16.2 감사). OLV-011 이후 모든 도메인 컨트롤러는 여기 못 박힌 봉투·에러·traceId·audit 위에서 구현된다.

**Invariants & Constraints:**

- **응답 봉투** = `ApiResponse<T>` record:
  - 성공: `{"success": true, "data": <T>, "meta": <PageMeta?>}`
  - 실패: `{"success": false, "error": ErrorBody}`
  - `@JsonInclude(NON_NULL)` 으로 빈 필드 자동 생략 — 즉 성공 응답에는 `error`/`meta` 키가 없고, 실패 응답에는 `data` 키가 없다.
- **에러 본문** = `ErrorBody{code, message, path, traceId, fieldErrors?}`. `code` 는 `ErrorCode` enum 의 name, `path` 는 `request.getRequestURI()`, `traceId` 는 MDC `traceId`(없으면 빈 문자열).
- **에러 코드 → HTTP** 매핑은 `ErrorCode.httpStatus()` 한 곳:
  ```
  MEMBER_NOT_FOUND      → 404
  PRODUCT_SOLD_OUT      → 409
  COUPON_INVALID        → 400
  INSUFFICIENT_INVENTORY→ 409
  PAYMENT_AMOUNT_MISMATCH→422
  IDEMPOTENCY_CONFLICT  → 409
  VALIDATION_FAILED     → 400  (handler 가 자동 부여, 직접 throw 금지)
  INTERNAL_ERROR        → 500  (handler 가 자동 부여, 직접 throw 금지)
  ```
  새 코드 추가 시 enum 에만 추가 — 핸들러 변경 불필요.
- **예외 throw 규칙**: 도메인 서비스는 `BusinessException(ErrorCode, String detail)` 만 던진다. detail 은 디버깅용 자유 문자열(예 `"id=42, brand=NULL"`). 필드 검증은 Bean Validation `@Valid` 로 충분 — 핸들러가 `MethodArgumentNotValidException` 을 잡아 fieldErrors 와 함께 400 으로 반환.
- **request-id**:
  - 헤더 이름 `X-Request-Id`, MDC 키 `traceId`. 둘 다 `RequestIdFilter` 의 public static 상수로 노출 (`RequestIdFilter.HEADER`, `RequestIdFilter.MDC_KEY`).
  - 클라이언트가 보낸 값이 **UUID 정규식** 에 맞지 않으면 새 UUID 발급 — 외부 입력 신뢰 금지.
  - try/finally `MDC.remove(...)` 보장 — virtual thread / 풀 재사용 누수 차단.
- **콘솔 로그 패턴**: `logback-spring.xml` 의 root appender 가 `%-5level [traceId=%X{traceId:-}] %logger{36} - %msg%n` — 모든 application 로그에 traceId 자동 포함.
- **감사 로그**:
  - `AuditLogger` 인터페이스 + `LogbackAuditLogger` 빈을 **주입**해서 사용 (정적 헬퍼 금지).
  - 호출 형식: `auditLogger.log("LOGIN_SUCCESS", Map.of("memberId", id, "ip", ip))`.
  - 출력: `${olive.audit.dir:-log}/audit-YYYY-MM-DD.log` 에 한 줄 JSON. 키: `@timestamp`, `event`, 호출자 attributes, MDC `traceId`, logger 메타.
  - 카테고리(이벤트 명) 가이드: `LOGIN_SUCCESS`/`LOGIN_FAILURE`/`ADMIN_MUTATION`/`PAYMENT_APPROVED`/`PAYMENT_FAILED`/`INVENTORY_RESERVED`/`INVENTORY_RELEASED`/`COUPON_USED`. 카테고리 enum 도입은 OLV-100/-130 시점에 점진적으로.
  - audit logger 는 `additivity=false` — 콘솔 root appender 로 흘러가지 않는다.
- **Spring Security 와의 분리**: `AuthenticationException`/`AccessDeniedException` 은 `GlobalExceptionHandler` 가 *명시적으로 rethrow* — `SecurityFilterChain` 의 EntryPoint/AccessDeniedHandler 가 401/403 을 책임진다.

**Files of interest:**

- `src/main/java/com/olive/commerce/common/api/ApiResponse.java`, `PageMeta.java`
- `src/main/java/com/olive/commerce/common/error/{ErrorCode,ErrorBody,FieldErrorEntry,BusinessException,GlobalExceptionHandler}.java`
- `src/main/java/com/olive/commerce/common/web/RequestIdFilter.java`
- `src/main/java/com/olive/commerce/common/audit/{AuditLogger,LogbackAuditLogger}.java`
- `src/main/resources/logback-spring.xml`
- PRD §11 (응답 패턴), §14·§16.2 (audit), §20.4 (idempotency 헤더)

**Decision log:**

- 2026-05-10 | OLV-004 | record + `@JsonInclude(NON_NULL)` 로 envelope 통일. Spring Boot 3.x 의 jackson 이 record 를 기본 직렬화하므로 별도 mixin/모듈 불필요.
- 2026-05-10 | OLV-004 | logback 의 `RollingFileAppender` 에서 `<file>` 을 **생략** — `fileNamePattern` 의 활성 파일이 곧 today 의 `audit-YYYY-MM-DD.log` 가 되어 rollover 를 기다리지 않고 즉시 검증 가능. (`<file>audit-current.log</file>` 형태였다면 자정 rollover 전까지 일자 파일에 라인이 쌓이지 않아 IT 가 깨짐.)
- 2026-05-10 | OLV-004 | `@WebMvcTest(controllers = X)` 가 **테스트 클래스 내부 nested static `@RestController`** 를 자동 등록하지 않는다 — 명시적 `@Import(...)` 필요. nested controller 사용 시 같은 파일에 핸들러/필터/컨트롤러를 모두 import 해야 컴파일·실행 성공.
- 2026-05-10 | OLV-004 | `@SpringBootTest` 가 SecurityConfig 를 component scan 으로 가져갈 때, `webEnvironment=NONE` + `SecurityFilterAutoConfiguration` 제외 조합은 `HttpSecurity` 빈 부재로 `securityFilterChain` 메서드 autowire 가 깨진다. audit IT 는 `webEnvironment=MOCK` 으로 servlet 컨텍스트만 띄워 회피 — DB 자동설정 3종(DataSource/JPA/Flyway)·UserDetailsService 는 여전히 제외 가능.
- 2026-05-10 | OLV-004 | LogstashEncoder 가 logback `<springProperty>` context 변수(`OLIVE_AUDIT_DIR`)를 JSON 필드로 자동 포함시킨다. 향후 OLV-130 에서 노이즈 제거가 필요하면 `<excludeMdcKeyName>` / `<excludeContextProperties>` 패턴으로 차단 — 현재는 디버깅 도움이 되어 유지.
- 2026-05-10 | OLV-004 | `${olive.audit.dir:-log}` 의 default 가 프로세스 CWD 기준 `log/`. K8s 배포 시 OLV-130 의 Helm values 가 절대경로(예 `/var/log/olive/audit`)를 주입할 예정.
- 2026-05-11 | OLV-021 | Public API는 내부 필드를 노출하지 않음. `PublicTreeNode`에는
  `sortOrder`가 없고, `AdminUpdateRequest`에는 `slug`가 없음(명세).
  테스트는 공개 필드만으로 검증해야 함.

**Last updated:** 2026-05-11 by OLV-021.
