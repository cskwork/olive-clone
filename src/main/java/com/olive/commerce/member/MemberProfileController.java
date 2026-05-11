package com.olive.commerce.member;

import com.olive.commerce.common.api.ApiResponse;
import com.olive.commerce.common.error.BusinessException;
import com.olive.commerce.common.error.ErrorCode;
import com.olive.commerce.common.security.AuthenticatedUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * GET /api/me — 현재 인증된 회원의 최소 프로필 echo.
 *
 * OLV-011 의 happy-path 검증 ("signup → login → /api/me echoes memberId") 용으로
 * 도입. 후속 회원 프로필 티켓이 본 컨트롤러를 확장한다.
 */
@RestController
@RequestMapping("/api/me")
public class MemberProfileController {

    private final MemberRepository members;

    public MemberProfileController(MemberRepository members) {
        this.members = members;
    }

    @GetMapping
    public ApiResponse<Map<String, Object>> me(@AuthenticationPrincipal AuthenticatedUser principal) {
        Member m = members.findById(principal.memberId())
            .orElseThrow(() -> new BusinessException(
                ErrorCode.MEMBER_NOT_FOUND, "memberId=" + principal.memberId()));
        return ApiResponse.success(Map.of(
            "memberId", m.getId(),
            "email", m.getEmail(),
            "name", m.getName(),
            "role", principal.role().name()
        ));
    }
}
