package com.olive.commerce.common.security;

import com.olive.commerce.member.MemberRole;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;

import java.util.List;

/**
 * Spring Security Resource Server 의 Jwt → Authentication 변환기.
 *
 * - principal 은 AuthenticatedUser(memberId, role).
 * - authority 는 단일 ROLE_<role>. RoleHierarchy 가 hasRole 평가 시 상위 권한을 자동 부여한다.
 * - 검증 실패 (sub 비정상, role claim 부재/오타, typ != access) 시 InvalidBearerTokenException
 *   을 던져 ResourceServer 가 401 envelope 으로 응답하도록 한다.
 */
public class JwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        String typ = jwt.getClaimAsString("typ");
        if (!"access".equals(typ)) {
            throw new InvalidBearerTokenException("typ is not access (got=" + typ + ")");
        }

        long memberId;
        try {
            memberId = Long.parseLong(jwt.getSubject());
        } catch (NullPointerException | NumberFormatException ex) {
            throw new InvalidBearerTokenException("subject is not numeric memberId", ex);
        }

        MemberRole role;
        try {
            role = MemberRole.valueOf(jwt.getClaimAsString("role"));
        } catch (NullPointerException | IllegalArgumentException ex) {
            throw new InvalidBearerTokenException("role claim invalid", ex);
        }

        AuthenticatedUser principal = new AuthenticatedUser(memberId, role);
        List<GrantedAuthority> authorities = List.of(new SimpleGrantedAuthority(role.authority()));
        return new UsernamePasswordAuthenticationToken(principal, jwt, authorities);
    }
}
