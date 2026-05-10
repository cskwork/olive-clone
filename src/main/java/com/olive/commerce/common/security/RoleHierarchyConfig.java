package com.olive.commerce.common.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.expression.method.DefaultMethodSecurityExpressionHandler;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.access.hierarchicalroles.RoleHierarchyImpl;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;

/**
 * 5 단계 역할 계층:
 *
 *   SUPER_ADMIN > {PRODUCT_ADMIN, ORDER_ADMIN, CS_MANAGER}
 *   {PRODUCT_ADMIN, ORDER_ADMIN, CS_MANAGER} > USER
 *
 * - URL 보안: SecurityFilterChain 의 authorizeHttpRequests 가 같은 RoleHierarchy 빈을
 *   AuthorizationManager 에 자동 적용한다 (Spring Security 6.3+).
 * - 메소드 보안: @PreAuthorize("hasRole('PRODUCT_ADMIN')") 평가 시
 *   MethodSecurityExpressionHandler 에 주입된 RoleHierarchy 가
 *   SUPER_ADMIN 토큰을 통과시킨다.
 */
@Configuration
@EnableMethodSecurity
public class RoleHierarchyConfig {

    @Bean
    public RoleHierarchy roleHierarchy() {
        return RoleHierarchyImpl.fromHierarchy("""
            ROLE_SUPER_ADMIN > ROLE_PRODUCT_ADMIN
            ROLE_SUPER_ADMIN > ROLE_ORDER_ADMIN
            ROLE_SUPER_ADMIN > ROLE_CS_MANAGER
            ROLE_PRODUCT_ADMIN > ROLE_USER
            ROLE_ORDER_ADMIN > ROLE_USER
            ROLE_CS_MANAGER > ROLE_USER
            """);
    }

    @Bean
    public DefaultMethodSecurityExpressionHandler methodSecurityExpressionHandler(RoleHierarchy roleHierarchy) {
        DefaultMethodSecurityExpressionHandler handler = new DefaultMethodSecurityExpressionHandler();
        handler.setRoleHierarchy(roleHierarchy);
        return handler;
    }
}
