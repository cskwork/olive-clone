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
        String detail = ex.getMessage() != null ? ex.getMessage() : code.name();
        // Log full detail server-side (may contain internal IDs — safe for operators only).
        log.warn("business_exception code={} detail={} path={}",
            code.name(), detail, request.getRequestURI());
        // Return a safe generic message keyed off the error code — never expose internal IDs.
        String clientMessage = safeClientMessage(code);
        ErrorBody body = ErrorBody.of(code.name(), clientMessage, request.getRequestURI(), traceId());
        return ResponseEntity.status(code.httpStatus()).body(ApiResponse.failure(body));
    }

    /**
     * Returns a safe client-facing message for a {@link ErrorCode}.
     * Internal entity IDs or raw exception details must not appear here.
     */
    private static String safeClientMessage(ErrorCode code) {
        return switch (code) {
            case MEMBER_NOT_FOUND -> "회원을 찾을 수 없습니다.";
            case MEMBER_GRADE_NOT_FOUND -> "회원 등급을 찾을 수 없습니다.";
            case ADDRESS_NOT_FOUND -> "주소를 찾을 수 없습니다.";
            case ADDRESS_NOT_OWNED -> "접근 권한이 없는 주소입니다.";
            case CANNOT_DELETE_ONLY_ADDRESS -> "마지막 주소는 삭제할 수 없습니다.";
            case PRODUCT_SOLD_OUT -> "품절된 상품입니다.";
            case COUPON_INVALID -> "유효하지 않은 쿠폰입니다.";
            case COUPON_NOT_FOUND -> "쿠폰을 찾을 수 없습니다.";
            case COUPON_EXPIRED -> "만료된 쿠폰입니다.";
            case COUPON_ALREADY_ISSUED -> "이미 발급된 쿠폰입니다.";
            case COUPON_ISSUE_LIMIT_EXCEEDED -> "쿠폰 발급 한도를 초과했습니다.";
            case COUPON_ALREADY_USED -> "이미 사용된 쿠폰입니다.";
            case INSUFFICIENT_INVENTORY -> "재고가 부족합니다.";
            case LOCK_ACQUISITION_FAILED -> "요청이 일시적으로 처리되지 못했습니다. 잠시 후 다시 시도해 주세요.";
            case PAYMENT_AMOUNT_MISMATCH -> "결제 금액이 주문 금액과 일치하지 않습니다.";
            case PAYMENT_NOT_FOUND -> "결제 정보를 찾을 수 없습니다.";
            case IDEMPOTENCY_CONFLICT -> "중복 요청입니다.";
            case PG_TIMEOUT -> "결제 게이트웨이 응답 시간이 초과되었습니다.";
            case PG_FAILED -> "결제 처리에 실패했습니다.";
            case PG_WEBHOOK_INVALID -> "유효하지 않은 웹훅 요청입니다.";
            case VALIDATION_FAILED -> "요청 본문 검증에 실패했습니다.";
            case AUTHENTICATION_REQUIRED -> "인증이 필요합니다.";
            case ACCESS_DENIED -> "접근 권한이 없습니다.";
            case EMAIL_ALREADY_USED -> "이미 사용 중인 이메일입니다.";
            case BAD_CREDENTIALS -> "이메일 또는 비밀번호가 올바르지 않습니다.";
            case ACCOUNT_LOCKED -> "계정이 잠겨 있습니다.";
            case INVALID_REFRESH_TOKEN -> "유효하지 않은 리프레시 토큰입니다.";
            case BRAND_SLUG_DUPLICATE -> "이미 사용 중인 브랜드 슬러그입니다.";
            case BRAND_NOT_FOUND -> "브랜드를 찾을 수 없습니다.";
            case CATEGORY_NOT_FOUND -> "카테고리를 찾을 수 없습니다.";
            case CATEGORY_HAS_PRODUCTS -> "상품이 등록된 카테고리는 삭제할 수 없습니다.";
            case CATEGORY_CYCLE_DETECTED -> "카테고리 순환 구조가 감지되었습니다.";
            case PRODUCT_NOT_FOUND -> "상품을 찾을 수 없습니다.";
            case PRODUCT_OPTION_NOT_FOUND -> "상품 옵션을 찾을 수 없습니다.";
            case CART_NOT_FOUND -> "장바구니를 찾을 수 없습니다.";
            case CART_ITEM_NOT_FOUND -> "장바구니 항목을 찾을 수 없습니다.";
            case CART_ITEM_INVALID_OPTION -> "유효하지 않은 장바구니 옵션입니다.";
            case INVALID_PRODUCT_STATE_TRANSITION -> "허용되지 않는 상품 상태 변경입니다.";
            case FILE_SIZE_EXCEEDED -> "파일 크기가 허용 한도를 초과했습니다.";
            case INVALID_FILE_TYPE -> "허용되지 않는 파일 형식입니다.";
            case INSUFFICIENT_POINTS -> "포인트가 부족합니다.";
            case POINT_HISTORY_NOT_FOUND -> "포인트 내역을 찾을 수 없습니다.";
            case ORDER_NOT_CANCELLABLE -> "취소할 수 없는 주문 상태입니다.";
            case ORDER_NOT_FOUND -> "주문을 찾을 수 없습니다.";
            case ORDER_NOT_OWNED -> "접근 권한이 없는 주문입니다.";
            case REFUND_NOT_FOUND -> "환불 정보를 찾을 수 없습니다.";
            case INVALID_STATUS_TRANSITION -> "허용되지 않는 상태 전환입니다.";
            case SEARCH_UNAVAILABLE -> "검색 서비스를 일시적으로 사용할 수 없습니다.";
            case INTERNAL_ERROR -> "서버 내부 오류가 발생했습니다.";
            case REVIEW_ELIGIBLE_ORDER_REQUIRED -> "리뷰 작성 가능한 주문이 필요합니다.";
            case REVIEW_ALREADY_EXISTS -> "이미 작성된 리뷰가 있습니다.";
            case REVIEW_NOT_FOUND -> "리뷰를 찾을 수 없습니다.";
            case REVIEW_NOT_OWNED -> "접근 권한이 없는 리뷰입니다.";
            case REVIEW_REPORT_NOT_FOUND -> "신고 내역을 찾을 수 없습니다.";
            case REVIEW_SELF_REPORT_NOT_ALLOWED -> "본인 리뷰는 신고할 수 없습니다.";
            case REVIEW_ALREADY_REPORTED -> "이미 신고된 리뷰입니다.";
        };
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
