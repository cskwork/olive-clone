package com.olive.commerce.auth;

import com.olive.commerce.auth.AuthDtos.LoginRequest;
import com.olive.commerce.auth.AuthDtos.LoginResponse;
import com.olive.commerce.auth.AuthDtos.LogoutResponse;
import com.olive.commerce.auth.AuthDtos.RefreshRequest;
import com.olive.commerce.auth.AuthDtos.SignupRequest;
import com.olive.commerce.auth.AuthDtos.SignupResponse;
import com.olive.commerce.common.api.ApiResponse;
import com.olive.commerce.common.security.AuthenticatedUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService auth;

    public AuthController(AuthService auth) {
        this.auth = auth;
    }

    @PostMapping("/signup")
    public ResponseEntity<ApiResponse<SignupResponse>> signup(@Valid @RequestBody SignupRequest req) {
        long memberId = auth.signup(req);
        return ResponseEntity
            .status(HttpStatus.CREATED)
            .body(ApiResponse.success(new SignupResponse(memberId)));
    }

    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest req,
                                            HttpServletRequest http) {
        String ip = clientIp(http);
        String ua = http.getHeader("User-Agent");
        return ApiResponse.success(auth.login(req, ip, ua));
    }

    @PostMapping("/refresh")
    public ApiResponse<LoginResponse> refresh(@Valid @RequestBody RefreshRequest req) {
        return ApiResponse.success(auth.refresh(req));
    }

    @PostMapping("/logout")
    public ApiResponse<LogoutResponse> logout(@AuthenticationPrincipal AuthenticatedUser principal) {
        int revoked = auth.logout(principal.memberId());
        return ApiResponse.success(new LogoutResponse(revoked));
    }

    private static String clientIp(HttpServletRequest req) {
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            int comma = xff.indexOf(',');
            return comma == -1 ? xff.trim() : xff.substring(0, comma).trim();
        }
        return req.getRemoteAddr();
    }
}
