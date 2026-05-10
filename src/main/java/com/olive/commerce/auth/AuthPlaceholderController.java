package com.olive.commerce.auth;

import com.olive.commerce.common.api.ApiResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 임시 auth 컨트롤러. /api/auth/** 가 permitAll 인지 검증할 수 있을 만큼만 동작.
 *
 * 실제 signup / login / refresh 는 OLV-011 이 본 컨트롤러를 대체한다.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthPlaceholderController {

    @PostMapping("/login")
    public ApiResponse<Map<String, String>> loginPlaceholder() {
        return ApiResponse.success(Map.of("status", "placeholder"));
    }
}
