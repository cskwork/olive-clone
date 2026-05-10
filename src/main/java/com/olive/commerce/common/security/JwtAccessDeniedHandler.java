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
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 인가 실패 (403) 응답을 ApiResponse.failure 봉투로 직접 직렬화한다.
 */
public class JwtAccessDeniedHandler implements AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    public JwtAccessDeniedHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void handle(HttpServletRequest request,
                       HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        ErrorBody body = ErrorBody.of(
            ErrorCode.ACCESS_DENIED.name(),
            "접근 권한이 없습니다.",
            request.getRequestURI(),
            traceId());
        ApiResponse<Void> envelope = ApiResponse.failure(body);

        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getOutputStream(), envelope);
    }

    private static String traceId() {
        String mdc = MDC.get(RequestIdFilter.MDC_KEY);
        return mdc != null ? mdc : "";
    }
}
