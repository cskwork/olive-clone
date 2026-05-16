package com.olive.testsupport.security;

import com.olive.commerce.common.api.ApiResponse;
import com.olive.commerce.common.config.SecurityConfig;
import com.olive.commerce.common.error.GlobalExceptionHandler;
import com.olive.commerce.common.security.AuthenticatedUser;
import com.olive.commerce.common.security.JwtConfig;
import com.olive.commerce.common.security.RoleHierarchyConfig;
import com.olive.commerce.common.web.RequestIdFilter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@SpringBootConfiguration
@EnableAutoConfiguration(exclude = {
    DataSourceAutoConfiguration.class,
    DataSourceTransactionManagerAutoConfiguration.class,
    HibernateJpaAutoConfiguration.class,
    FlywayAutoConfiguration.class
})
@Import({
    SecurityConfig.class,
    JwtConfig.class,
    RoleHierarchyConfig.class,
    GlobalExceptionHandler.class,
    RequestIdFilter.class,
    SecurityFilterChainTestApp.CartProbeController.class,
    SecurityFilterChainTestApp.AdminProductProbeController.class,
    SecurityFilterChainTestApp.AuthProbeController.class
})
public class SecurityFilterChainTestApp {

    @RestController
    @RequestMapping("/api/cart")
    static class CartProbeController {
        @GetMapping
        ApiResponse<Map<String, Object>> get(@AuthenticationPrincipal AuthenticatedUser principal) {
            return ApiResponse.success(Map.of("memberId", principal.memberId()));
        }
    }

    @RestController
    @RequestMapping("/api/admin/products")
    static class AdminProductProbeController {
        @PostMapping
        @PreAuthorize("hasRole('PRODUCT_ADMIN')")
        ResponseEntity<ApiResponse<Map<String, Object>>> create(
                @AuthenticationPrincipal AuthenticatedUser principal) {
            return ResponseEntity.status(201).body(ApiResponse.success(Map.of(
                    "createdBy", principal.memberId(),
                    "role", principal.role().name()
            )));
        }
    }

    @RestController
    @RequestMapping("/api/auth")
    static class AuthProbeController {
        @PostMapping("/login")
        ApiResponse<Void> login(@Valid @RequestBody LoginRequest request) {
            return ApiResponse.success(null);
        }
    }

    record LoginRequest(@NotBlank String email, @NotBlank String password) {
    }
}
