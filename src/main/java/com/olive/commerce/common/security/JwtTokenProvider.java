package com.olive.commerce.common.security;

import com.olive.commerce.member.MemberRole;

public interface JwtTokenProvider {

    /**
     * Access 토큰 발급. 30분 TTL, RS256 서명, claim: sub/role/typ=access/iss/iat/exp.
     */
    String issueAccess(long memberId, MemberRole role);

    /**
     * Refresh 토큰 발급. 14일 TTL, claim: sub/typ=refresh/jti/iss/iat/exp.
     * JTI 평문은 호출자가 hash 후 member_refresh_tokens 에 저장 (OLV-011 책임).
     */
    String issueRefresh(long memberId);

    /**
     * Access 토큰 검증 + claim 추출. 만료/서명/typ 불일치 시 JwtValidationException.
     */
    JwtClaims parseAccess(String token);
}
