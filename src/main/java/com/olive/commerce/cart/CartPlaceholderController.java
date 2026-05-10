package com.olive.commerce.cart;

import com.olive.commerce.common.api.ApiResponse;
import com.olive.commerce.common.security.AuthenticatedUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 임시 cart 컨트롤러. OLV-005 의 SecurityFilterChain 가
 *  - 미인증 → 401
 *  - USER 이상 토큰 → 200
 * 을 보장하는 것을 검증할 수 있을 만큼만 동작한다.
 *
 * 실제 cart 도메인은 OLV-040 이 본 컨트롤러를 대체한다.
 */
@RestController
@RequestMapping("/api/cart")
public class CartPlaceholderController {

    @GetMapping
    public ApiResponse<Map<String, Object>> getCart(@AuthenticationPrincipal AuthenticatedUser principal) {
        return ApiResponse.success(Map.of(
            "memberId", principal.memberId(),
            "items", List.of()
        ));
    }
}
