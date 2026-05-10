package com.olive.commerce.common.security;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.io.Resource;

import java.time.Duration;

/**
 * application.yml 의 olive.security.jwt.* 매핑.
 *
 * 예시:
 *   olive.security.jwt.issuer: olive-commerce
 *   olive.security.jwt.access-ttl: PT30M
 *   olive.security.jwt.refresh-ttl: P14D
 *   olive.security.jwt.private-key-location: classpath:keys/app.key
 *   olive.security.jwt.public-key-location:  classpath:keys/app.pub
 */
@ConfigurationProperties(prefix = "olive.security.jwt")
public record JwtProperties(
    @NotBlank String issuer,
    @NotNull Duration accessTtl,
    @NotNull Duration refreshTtl,
    @NotNull Resource privateKeyLocation,
    @NotNull Resource publicKeyLocation
) {}
