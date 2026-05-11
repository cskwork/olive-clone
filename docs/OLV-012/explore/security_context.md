# OLV-012 Explore: Security Context

## SecurityConfig (관련 부분)

```java
.requestMatchers("/api/**").hasRole("USER")
```

- `/api/me`는 `/api/**` 패턴에 포함되어 자동으로 USER 역할 요구
- 401 (미인증)은 Spring Security가 자동 처리
- 403 (권한 없음)도 자동 처리

## AuthenticatedUser

```java
public record AuthenticatedUser(long memberId, MemberRole role) {}
```

- 컨트롤러에서 `@AuthenticationPrincipal AuthenticatedUser principal`로 주입
- `principal.memberId()`로 현재 회원 ID 획득

## 소유권 검증 패턴 (AC 요구사항)

- 회원 A가 회원 B의 주소지에 접근 시도 시 403 반환 필요
- Repository에서 `findByIdAndMemberId(id, memberId)` 패턴 사용 권장
- 또는 서비스 계층에서 명시적 검증: `if (address.getMemberId() != principal.memberId()) throw ...`
