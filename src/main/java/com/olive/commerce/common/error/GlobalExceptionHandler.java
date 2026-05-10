package com.olive.commerce.common.error;

import com.olive.commerce.common.api.ApiResponse;
import com.olive.commerce.common.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException ex,
                                                            HttpServletRequest request) {
        ErrorCode code = ex.errorCode();
        String message = ex.getMessage() != null ? ex.getMessage() : code.name();
        log.warn("business_exception code={} message={} path={}",
            code.name(), message, request.getRequestURI());
        ErrorBody body = ErrorBody.of(code.name(), message, request.getRequestURI(), traceId());
        return ResponseEntity.status(code.httpStatus()).body(ApiResponse.failure(body));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException ex,
                                                              HttpServletRequest request) {
        List<FieldErrorEntry> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
            .map(GlobalExceptionHandler::toFieldErrorEntry)
            .toList();
        ErrorBody body = ErrorBody.withFieldErrors(
            ErrorCode.VALIDATION_FAILED.name(),
            "요청 본문 검증에 실패했습니다.",
            request.getRequestURI(),
            traceId(),
            fieldErrors);
        return ResponseEntity.status(ErrorCode.VALIDATION_FAILED.httpStatus())
            .body(ApiResponse.failure(body));
    }

    // Spring Security 인증/인가 예외는 SecurityFilterChain의 EntryPoint/AccessDeniedHandler가
    // 처리하도록 그대로 통과시킨다. 컨트롤러에서 흘러나온 경우만 안전망으로 잡는다.
    @ExceptionHandler({AuthenticationException.class, AccessDeniedException.class})
    public void rethrowSecurity(RuntimeException ex) {
        throw ex;
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUncaught(Exception ex,
                                                            HttpServletRequest request) {
        log.error("uncaught_exception path={} type={}",
            request.getRequestURI(), ex.getClass().getName(), ex);
        ErrorBody body = ErrorBody.of(
            ErrorCode.INTERNAL_ERROR.name(),
            "서버 내부 오류가 발생했습니다.",
            request.getRequestURI(),
            traceId());
        return ResponseEntity.status(ErrorCode.INTERNAL_ERROR.httpStatus())
            .body(ApiResponse.failure(body));
    }

    private static String traceId() {
        String mdc = MDC.get(RequestIdFilter.MDC_KEY);
        return mdc != null ? mdc : "";
    }

    private static FieldErrorEntry toFieldErrorEntry(FieldError fe) {
        return new FieldErrorEntry(fe.getField(),
            fe.getDefaultMessage(),
            fe.getRejectedValue());
    }
}
