# OLV-004 — 공통 모듈: 응답 봉투 / 에러 / Request-ID / 감사 로그

## 사용자(개발자/운영자) 관점에서 무엇이 생겼나

1. **모든 API 컨트롤러는 `ApiResponse<T>` 한 가지 형태로 응답한다.**
   - 성공: `{"success": true, "data": <T>, "meta": <PageMeta?>}` (null 필드는 직렬화에서 자동 제거)
   - 실패: `{"success": false, "error": {"code": "...", "message": "...", "path": "...", "traceId": "..."}}`
   - 페이지: `meta = {"page": 1, "size": 20, "total": 132}`

2. **에러 코드와 HTTP 상태가 한 곳에 묶여있다(`ErrorCode` enum).**
   | code | HTTP |
   |---|---|
   | MEMBER_NOT_FOUND | 404 |
   | PRODUCT_SOLD_OUT | 409 |
   | COUPON_INVALID | 400 |
   | INSUFFICIENT_INVENTORY | 409 |
   | PAYMENT_AMOUNT_MISMATCH | 422 |
   | IDEMPOTENCY_CONFLICT | 409 |
   | VALIDATION_FAILED | 400 |
   | INTERNAL_ERROR | 500 |

   서비스/도메인 레이어는 `throw new BusinessException(ErrorCode.MEMBER_NOT_FOUND, "id=42")` 만 던지면 컨트롤러 어드바이스가 알아서 status + envelope 으로 바꿔 응답한다.

3. **모든 요청은 traceId 를 가진다.**
   - 클라이언트가 `X-Request-Id` 헤더를 보내면 (UUID 형식) 그대로 사용.
   - 없거나 형식 불량(예: SQLi 시도 같은 문자열) 이면 서버가 새 UUID 를 발급.
   - SLF4J MDC `traceId` 로 로그에 자동 삽입(콘솔 패턴 `[traceId=...]`), 응답 헤더 `X-Request-Id` 로 클라이언트에 echo.
   - 요청 종료 시 try/finally 로 MDC 가 초기화되어 다음 요청에 누수 없음.

4. **감사 로그가 일자 롤링 JSON 파일로 분리된다.**
   - 구현체 `LogbackAuditLogger` 빈을 주입받아 `auditLogger.log("LOGIN_SUCCESS", Map.of("memberId", 42))` 호출.
   - 결과: `${olive.audit.dir:-log}/audit-2026-05-10.log` 에 한 줄 JSON 추가
     ```json
     {"@timestamp":"2026-05-10T19:24:18.422+09:00","@version":"1","message":"LOGIN_SUCCESS","logger_name":"olive.audit","thread_name":"main","level":"INFO","level_value":20000,"event":"LOGIN_SUCCESS","memberId":42,"ip":"127.0.0.1","traceId":"22222222-2222-2222-2222-222222222222"}
     ```
   - 90일 회전, 2GB 총 용량 cap. OLV-130 (Prometheus stack) 이 그대로 스크레이프.
   - 콘솔 로그와 audit 파일은 완전히 분리(`additivity=false`).

## 정확히 어떤 파일이 추가/수정됐나

신규 (production):
- `src/main/java/com/olive/commerce/common/api/ApiResponse.java`
- `src/main/java/com/olive/commerce/common/api/PageMeta.java`
- `src/main/java/com/olive/commerce/common/error/ErrorCode.java`
- `src/main/java/com/olive/commerce/common/error/ErrorBody.java`
- `src/main/java/com/olive/commerce/common/error/FieldErrorEntry.java`
- `src/main/java/com/olive/commerce/common/error/BusinessException.java`
- `src/main/java/com/olive/commerce/common/error/GlobalExceptionHandler.java`
- `src/main/java/com/olive/commerce/common/web/RequestIdFilter.java`
- `src/main/java/com/olive/commerce/common/audit/AuditLogger.java`
- `src/main/java/com/olive/commerce/common/audit/LogbackAuditLogger.java`
- `src/main/resources/logback-spring.xml`

신규 (tests):
- `src/test/java/com/olive/commerce/common/error/GlobalExceptionHandlerTest.java` (MockMvc 5건)
- `src/test/java/com/olive/commerce/common/web/RequestIdFilterTest.java` (필터 단위 4건)
- `src/test/java/com/olive/commerce/common/audit/LogbackAuditLoggerIT.java` (Spring Boot context 1건)

수정:
- `build.gradle.kts` — `net.logstash.logback:logstash-logback-encoder:7.4` 추가.

## 어떻게 후속 티켓이 사용하나

OLV-010 (member) 컨트롤러 예:
```java
@GetMapping("/{id}")
public ApiResponse<MemberResponse> get(@PathVariable Long id) {
    return ApiResponse.success(memberService.findById(id));
}
```
서비스가 `BusinessException(ErrorCode.MEMBER_NOT_FOUND, "id=" + id)` 을 던지면 컨트롤러는 그대로 통과시키고, `GlobalExceptionHandler` 가 404 + envelope 으로 자동 변환.

감사 카테고리 사용:
```java
@Service
@RequiredArgsConstructor
public class LoginService {
    private final AuditLogger auditLogger;
    public TokenPair login(String email, String password) {
        // ...
        auditLogger.log("LOGIN_SUCCESS", Map.of("memberId", member.id(), "ip", ip));
        return tokens;
    }
}
```
