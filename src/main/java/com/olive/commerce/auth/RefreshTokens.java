package com.olive.commerce.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Refresh 토큰 평문 → SHA-256 hex 64자.
 *
 * `member_refresh_tokens.token_hash CHAR(64)` 와 정확히 일치하는 표현으로
 * 변환 — DB 에는 평문이 절대 들어가지 않는다 (OLV-005 contract).
 */
final class RefreshTokens {

    private RefreshTokens() {}

    static String sha256Hex(String token) {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
        byte[] hash = md.digest(token.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
