package com.olive.commerce.common.security;

import com.olive.commerce.member.MemberRole;

import java.time.Instant;

public record JwtClaims(
    long memberId,
    MemberRole role,
    Instant expiresAt
) {}
