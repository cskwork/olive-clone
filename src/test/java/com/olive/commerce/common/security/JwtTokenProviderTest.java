package com.olive.commerce.common.security;

import com.olive.commerce.member.MemberRole;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtTokenProviderTest {

    private static KeyPair keyPair;

    @BeforeAll
    static void generateKeys() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        keyPair = gen.generateKeyPair();
    }

    private NimbusJwtTokenProvider providerAt(Clock clock) {
        return new NimbusJwtTokenProvider(
            (RSAPrivateKey) keyPair.getPrivate(),
            (RSAPublicKey) keyPair.getPublic(),
            "olive-commerce",
            Duration.ofMinutes(30),
            Duration.ofDays(14),
            clock);
    }

    @Test
    void issueAccess_roundTrip_decodesSameClaims() {
        Clock fixed = Clock.fixed(Instant.parse("2026-05-10T10:00:00Z"), ZoneId.of("UTC"));
        JwtTokenProvider provider = providerAt(fixed);

        String token = provider.issueAccess(42L, MemberRole.USER);
        JwtClaims claims = provider.parseAccess(token);

        assertThat(claims.memberId()).isEqualTo(42L);
        assertThat(claims.role()).isEqualTo(MemberRole.USER);
        assertThat(claims.expiresAt()).isEqualTo(fixed.instant().plus(Duration.ofMinutes(30)));
    }

    @Test
    void issueAccess_expiredToken_isRejected() {
        Clock issuedAt = Clock.fixed(Instant.parse("2026-05-10T10:00:00Z"), ZoneId.of("UTC"));
        Clock thirtyOneMinutesLater = Clock.offset(issuedAt, Duration.ofMinutes(31));

        String token = providerAt(issuedAt).issueAccess(42L, MemberRole.USER);

        assertThatThrownBy(() -> providerAt(thirtyOneMinutesLater).parseAccess(token))
            .isInstanceOf(JwtValidationException.class)
            .hasMessageContaining("expired");
    }

    @Test
    void issueAccess_setsTypAccessAndRoleClaim() throws ParseException, JOSEException {
        Clock fixed = Clock.fixed(Instant.parse("2026-05-10T10:00:00Z"), ZoneId.of("UTC"));
        String token = providerAt(fixed).issueAccess(42L, MemberRole.PRODUCT_ADMIN);

        SignedJWT parsed = SignedJWT.parse(token);
        assertThat(parsed.verify(new RSASSAVerifier((RSAPublicKey) keyPair.getPublic()))).isTrue();
        assertThat(parsed.getJWTClaimsSet().getStringClaim("typ")).isEqualTo("access");
        assertThat(parsed.getJWTClaimsSet().getStringClaim("role")).isEqualTo("PRODUCT_ADMIN");
        assertThat(parsed.getJWTClaimsSet().getIssuer()).isEqualTo("olive-commerce");
        assertThat(parsed.getJWTClaimsSet().getSubject()).isEqualTo("42");
    }

    @Test
    void issueRefresh_setsTypRefreshAndJti() throws ParseException {
        Clock fixed = Clock.fixed(Instant.parse("2026-05-10T10:00:00Z"), ZoneId.of("UTC"));
        String token = providerAt(fixed).issueRefresh(42L);

        SignedJWT parsed = SignedJWT.parse(token);
        assertThat(parsed.getJWTClaimsSet().getStringClaim("typ")).isEqualTo("refresh");
        assertThat(parsed.getJWTClaimsSet().getJWTID()).isNotBlank();
        assertThat(parsed.getJWTClaimsSet().getExpirationTime().toInstant())
            .isEqualTo(fixed.instant().plus(Duration.ofDays(14)));
    }

    @Test
    void parseAccess_refreshTokenRejected() {
        Clock fixed = Clock.fixed(Instant.parse("2026-05-10T10:00:00Z"), ZoneId.of("UTC"));
        JwtTokenProvider provider = providerAt(fixed);
        String refresh = provider.issueRefresh(42L);

        assertThatThrownBy(() -> provider.parseAccess(refresh))
            .isInstanceOf(JwtValidationException.class)
            .hasMessageContaining("typ");
    }

    @Test
    void parseAccess_tamperedSignatureRejected() {
        Clock fixed = Clock.fixed(Instant.parse("2026-05-10T10:00:00Z"), ZoneId.of("UTC"));
        String token = providerAt(fixed).issueAccess(42L, MemberRole.USER);
        // Flip a char in the signature segment's interior — the *last* base64url
        // char of an RS256-2048 signature only encodes 2 useful bits, so single-char
        // tampering at the end may collapse to padding-only bits and verify successfully.
        int lastDot = token.lastIndexOf('.');
        int target = lastDot + (token.length() - lastDot) / 2;
        char original = token.charAt(target);
        char swapped = original == 'A' ? 'B' : 'A';
        String tampered = token.substring(0, target) + swapped + token.substring(target + 1);

        assertThatThrownBy(() -> providerAt(fixed).parseAccess(tampered))
            .isInstanceOf(JwtValidationException.class);
    }
}
