package com.olive.commerce.common.security;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;

@Configuration
@EnableConfigurationProperties(JwtProperties.class)
public class JwtConfig {

    @Bean
    public RSAPrivateKey jwtPrivateKey(JwtProperties props) {
        return RsaKeyLoader.loadPrivate(props.privateKeyLocation());
    }

    @Bean
    public RSAPublicKey jwtPublicKey(JwtProperties props) {
        return RsaKeyLoader.loadPublic(props.publicKeyLocation());
    }

    @Bean
    public Clock jwtClock() {
        return Clock.systemUTC();
    }

    @Bean
    public JwtTokenProvider jwtTokenProvider(JwtProperties props,
                                             RSAPrivateKey privateKey,
                                             RSAPublicKey publicKey,
                                             Clock jwtClock) {
        return new NimbusJwtTokenProvider(
            privateKey,
            publicKey,
            props.issuer(),
            props.accessTtl(),
            props.refreshTtl(),
            jwtClock);
    }

    /**
     * Resource Server 검증용 JwtDecoder.
     * - public key 로 RS256 서명 검증
     * - issuer 검증
     * - exp/nbf 시간 검증 (Spring 기본)
     */
    @Bean
    public JwtDecoder jwtDecoder(RSAPublicKey publicKey, JwtProperties props) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(publicKey).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(props.issuer()));
        return decoder;
    }
}
