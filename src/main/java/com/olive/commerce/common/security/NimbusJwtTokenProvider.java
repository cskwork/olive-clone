package com.olive.commerce.common.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.olive.commerce.member.MemberRole;

import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

public class NimbusJwtTokenProvider implements JwtTokenProvider {

    private static final String TYPE_ACCESS = "access";
    private static final String TYPE_REFRESH = "refresh";

    private final RSAPrivateKey privateKey;
    private final RSAPublicKey publicKey;
    private final String issuer;
    private final Duration accessTtl;
    private final Duration refreshTtl;
    private final Clock clock;

    public NimbusJwtTokenProvider(RSAPrivateKey privateKey,
                                  RSAPublicKey publicKey,
                                  String issuer,
                                  Duration accessTtl,
                                  Duration refreshTtl,
                                  Clock clock) {
        this.privateKey = privateKey;
        this.publicKey = publicKey;
        this.issuer = issuer;
        this.accessTtl = accessTtl;
        this.refreshTtl = refreshTtl;
        this.clock = clock;
    }

    @Override
    public String issueAccess(long memberId, MemberRole role) {
        Instant now = clock.instant();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
            .issuer(issuer)
            .subject(Long.toString(memberId))
            .claim("role", role.name())
            .claim("typ", TYPE_ACCESS)
            .issueTime(Date.from(now))
            .expirationTime(Date.from(now.plus(accessTtl)))
            .build();
        return sign(claims);
    }

    @Override
    public String issueRefresh(long memberId) {
        Instant now = clock.instant();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
            .issuer(issuer)
            .subject(Long.toString(memberId))
            .claim("typ", TYPE_REFRESH)
            .jwtID(UUID.randomUUID().toString())
            .issueTime(Date.from(now))
            .expirationTime(Date.from(now.plus(refreshTtl)))
            .build();
        return sign(claims);
    }

    @Override
    public JwtClaims parseAccess(String token) {
        return parse(token, TYPE_ACCESS);
    }

    @Override
    public JwtClaims parseRefresh(String token) {
        return parse(token, TYPE_REFRESH);
    }

    private JwtClaims parse(String token, String expectedTyp) {
        SignedJWT signed;
        try {
            signed = SignedJWT.parse(token);
        } catch (ParseException ex) {
            throw new JwtValidationException("malformed jwt", ex);
        }
        try {
            if (!signed.verify(new RSASSAVerifier(publicKey))) {
                throw new JwtValidationException("invalid signature");
            }
        } catch (JOSEException ex) {
            throw new JwtValidationException("signature verification failed", ex);
        }

        JWTClaimsSet claims;
        try {
            claims = signed.getJWTClaimsSet();
        } catch (ParseException ex) {
            throw new JwtValidationException("malformed claims", ex);
        }

        if (!issuer.equals(claims.getIssuer())) {
            throw new JwtValidationException("issuer mismatch");
        }

        String typ;
        try {
            typ = claims.getStringClaim("typ");
        } catch (ParseException ex) {
            throw new JwtValidationException("typ claim invalid", ex);
        }
        if (!expectedTyp.equals(typ)) {
            throw new JwtValidationException("typ is not " + expectedTyp + " (got=" + typ + ")");
        }

        Date exp = claims.getExpirationTime();
        if (exp == null) {
            throw new JwtValidationException("missing exp");
        }
        if (!clock.instant().isBefore(exp.toInstant())) {
            throw new JwtValidationException("token expired at " + exp.toInstant());
        }

        long memberId;
        try {
            memberId = Long.parseLong(claims.getSubject());
        } catch (NumberFormatException ex) {
            throw new JwtValidationException("subject is not numeric memberId", ex);
        }

        MemberRole role;
        if (TYPE_ACCESS.equals(expectedTyp)) {
            try {
                String roleClaim = claims.getStringClaim("role");
                role = MemberRole.valueOf(roleClaim);
            } catch (ParseException | IllegalArgumentException | NullPointerException ex) {
                throw new JwtValidationException("role claim invalid", ex);
            }
        } else {
            role = MemberRole.USER;
        }

        return new JwtClaims(memberId, role, exp.toInstant());
    }

    private String sign(JWTClaimsSet claims) {
        SignedJWT signed = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
        try {
            signed.sign(new RSASSASigner(privateKey));
        } catch (JOSEException ex) {
            throw new IllegalStateException("failed to sign jwt", ex);
        }
        return signed.serialize();
    }
}
