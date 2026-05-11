package com.olive.commerce.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * /api/auth 요청·응답 DTO 모음. 컨트롤러 시그니처를 한눈에 보기 위한 묶음 record.
 */
public final class AuthDtos {

    private AuthDtos() {}

    public record SignupRequest(
        @NotBlank
        @Pattern(regexp = "^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$",
                 message = "이메일 형식이 올바르지 않습니다.")
        String email,

        @NotBlank
        @Size(min = 8, max = 100, message = "비밀번호는 8자 이상이어야 합니다.")
        String password,

        @NotBlank
        @Size(max = 100)
        String name,

        @Pattern(regexp = "^01[016789]-?\\d{3,4}-?\\d{4}$",
                 message = "휴대전화 형식이 올바르지 않습니다.")
        String phone
    ) {}

    public record SignupResponse(long memberId) {}

    public record LoginRequest(
        @NotBlank String email,
        @NotBlank String password
    ) {}

    public record LoginResponse(
        String accessToken,
        String refreshToken,
        long expiresInSec
    ) {}

    public record RefreshRequest(
        @NotBlank String refreshToken
    ) {}

    public record LogoutResponse(int revokedTokens) {}
}
