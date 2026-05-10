package com.olive.commerce.common.security;

import com.olive.commerce.member.MemberRole;

/**
 * 인증된 호출자 표현. SecurityContext 의 principal 로 노출되며,
 * 컨트롤러는 @AuthenticationPrincipal AuthenticatedUser 로 주입받는다.
 */
public record AuthenticatedUser(long memberId, MemberRole role) {}
