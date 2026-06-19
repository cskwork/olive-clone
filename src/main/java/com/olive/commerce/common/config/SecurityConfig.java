package com.olive.commerce.common.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.olive.commerce.common.security.JwtAccessDeniedHandler;
import com.olive.commerce.common.security.JwtAuthenticationConverter;
import com.olive.commerce.common.security.JwtAuthenticationEntryPoint;
import com.olive.commerce.common.web.RateLimitFilter;
import com.olive.commerce.common.web.RateLimitProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableConfigurationProperties(RateLimitProperties.class)
public class SecurityConfig {

    @Bean
    public AuthenticationEntryPoint authenticationEntryPoint(ObjectMapper objectMapper) {
        return new JwtAuthenticationEntryPoint(objectMapper);
    }

    @Bean
    public AccessDeniedHandler accessDeniedHandler(ObjectMapper objectMapper) {
        return new JwtAccessDeniedHandler(objectMapper);
    }

    /**
     * 비밀번호 해시 — bcrypt cost 12 (PRD §14.3, llm-wiki/98-security.md).
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    /**
     * CORS configuration source.
     *
     * <p>Allowed origins are read from {@code olive.cors.allowed-origins} (comma-separated).
     * Use explicit origins — wildcard "*" is intentionally avoided so that
     * {@code allowCredentials=true} (required for cookie-based auth flows) is safe.
     *
     * <p>Default: http://localhost:5173 (Vite dev server), http://localhost:8080 (Spring dev).
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource(
            @Value("${olive.cors.allowed-origins:http://localhost:5173,http://localhost:8080}")
            List<String> allowedOrigins) {

        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(allowedOrigins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of(
            "Authorization", "Content-Type", "X-Request-Id", "Accept", "Origin"));
        config.setExposedHeaders(List.of("X-Request-Id"));
        config.setAllowCredentials(true);
        // Preflight cache duration: 30 minutes
        config.setMaxAge(1800L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }

    /**
     * Per-IP rate-limit filter bean.
     *
     * <p>The filter is inserted into the security filter chain via {@code addFilterBefore}.
     * A {@link FilterRegistrationBean} wrapping it is exposed with {@code enabled=false}
     * so Spring Boot's auto-configuration does not register it as a second, separate
     * servlet filter — which would cause every matching request to be filtered twice.
     */
    @Bean
    public FilterRegistrationBean<RateLimitFilter> rateLimitFilterRegistration(
            RateLimitProperties props, ObjectMapper objectMapper, Environment environment) {
        FilterRegistrationBean<RateLimitFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new RateLimitFilter(props, objectMapper, environment));
        // Disable auto-registration: the filter is wired into the security chain manually.
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   AuthenticationEntryPoint entryPoint,
                                                   AccessDeniedHandler accessDeniedHandler,
                                                   CorsConfigurationSource corsConfigurationSource,
                                                   FilterRegistrationBean<RateLimitFilter> rateLimitFilterRegistration)
            throws Exception {
        http
            .cors(cors -> cors.configurationSource(corsConfigurationSource))
            .csrf(csrf -> csrf.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .httpBasic(basic -> basic.disable())
            .formLogin(form -> form.disable())
            .addFilterBefore(rateLimitFilterRegistration.getFilter(),
                UsernamePasswordAuthenticationFilter.class)
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(
                    "/",
                    "/products",
                    "/products/**",
                    "/css/**",
                    "/js/**",
                    "/images/**",
                    "/app",
                    "/app/**",
                    "/favicon.ico",
                    "/actuator/health",
                    "/actuator/health/**",
                    "/actuator/info",
                    "/swagger-ui.html",
                    "/swagger-ui/**",
                    "/v3/api-docs",
                    "/v3/api-docs/**"
                ).permitAll()
                .requestMatchers("/api/auth/**").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/payments/webhook").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/products/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/search/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/brands").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/brands/*/products").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/categories").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/categories/*/products").permitAll()
                .requestMatchers("/api/admin/**")
                    .hasAnyRole("CS_MANAGER", "PRODUCT_ADMIN", "ORDER_ADMIN", "SUPER_ADMIN")
                .requestMatchers("/api/cart/anonymous/**").permitAll()
                .requestMatchers("/api/**").hasRole("USER")
                .anyRequest().authenticated()
            )
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt.jwtAuthenticationConverter(new JwtAuthenticationConverter()))
                .authenticationEntryPoint(entryPoint)
                .accessDeniedHandler(accessDeniedHandler)
            )
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint(entryPoint)
                .accessDeniedHandler(accessDeniedHandler)
            );
        return http.build();
    }
}
