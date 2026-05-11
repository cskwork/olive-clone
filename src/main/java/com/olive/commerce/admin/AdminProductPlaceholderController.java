package com.olive.commerce.admin;

import com.olive.commerce.common.api.ApiResponse;
import com.olive.commerce.common.security.AuthenticatedUser;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 임시 admin product 컨트롤러. OLV-005 의 RoleHierarchy + @PreAuthorize 통합 검증용.
 *
 * - URL 매처는 /api/admin/** 에서 4 가지 admin 역할만 통과시킨다.
 * - @PreAuthorize 는 그 안에서 PRODUCT_ADMIN 또는 (hierarchy 의해 SUPER_ADMIN) 만 통과.
 *
 * 실제 admin 상품 등록은 OLV-022 가 대체한다.
 *
 * OLV-022 구현 완료로 비활성화 (@RestController 제거됨)
 */
// @RestController // OLV-022: ProductAdminController로 대체됨
@RequestMapping("/api/admin/products")
public class AdminProductPlaceholderController {

    @PostMapping
    @PreAuthorize("hasRole('PRODUCT_ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> create(
            @AuthenticationPrincipal AuthenticatedUser principal) {
        ApiResponse<Map<String, Object>> body = ApiResponse.success(Map.of(
            "createdBy", principal.memberId(),
            "role", principal.role().name()
        ));
        return ResponseEntity.status(201).body(body);
    }
}
