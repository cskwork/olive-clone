package com.olive.commerce.common.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.olive.commerce.common.api.ApiResponse;
import com.olive.commerce.common.error.ErrorBody;
import com.olive.commerce.common.error.ErrorCode;
import com.olive.commerce.common.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 인증 실패 (401) 응답을 ApiResponse.failure 봉투로 직접 직렬화한다.
 * RequestIdFilter 가 이미 MDC 에 traceId 를 채웠으므로 그대로 끌어다 쓴다.
 */
public class JwtAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    public JwtAuthenticationEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request,
                         HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        ErrorBody body = ErrorBody.of(
            ErrorCode.AUTHENTICATION_REQUIRED.name(),
            "인증이 필요합니다.",
            request.getRequestURI(),
            traceId());
        ApiResponse<Void> envelope = ApiResponse.failure(body);

        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getOutputStream(), envelope);
    }

    private static String traceId() {
        String mdc = MDC.get(RequestIdFilter.MDC_KEY);
        return mdc != null ? mdc : "";
    }
}
