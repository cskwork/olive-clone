# OLV-012 Explore: API Conventions

## ApiResponse<T> 봉투

```java
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(
    boolean success,
    T data,
    ErrorBody error,
    PageMeta meta
) {
    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(true, data, null, null);
    }
}
```

## 기존 MemberProfileController (OLV-011)

```java
@RestController
@RequestMapping("/api/me")
public class MemberProfileController {
    @GetMapping
    public ApiResponse<Map<String, Object>> me(@AuthenticationPrincipal AuthenticatedUser principal) {
        Member m = members.findById(principal.memberId())
            .orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND, ...));
        return ApiResponse.success(Map.of(
            "memberId", m.getId(),
            "email", m.getEmail(),
            "name", m.getName(),
            "role", principal.role().name()
        ));
    }
}
```

## 추가 필요 ErrorCode (추정)

- `ADDRESS_NOT_FOUND` (404) — 존재하지 않는 주소지
- `ADDRESS_DELETE_FORBIDDEN` (403) — 삭제 제한 (유일 주소지 + 주문 참조)

## 테스트 패턴 (AuthApiIT 참고)

```java
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class XyzApiIT extends PostgresIntegrationSupport {
    @BeforeEach void cleanState() {
        // TRUNCATE로 깨끗한 시작점
    }

    @Test
    void happyPath() throws Exception {
        mockMvc.perform(get("/api/me")
                .header("Authorization", "Bearer " + access))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.memberId").value(...));
    }
}
```
