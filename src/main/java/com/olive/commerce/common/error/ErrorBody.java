package com.olive.commerce.common.error;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorBody(
    String code,
    String message,
    String path,
    String traceId,
    List<FieldErrorEntry> fieldErrors
) {
    public static ErrorBody of(String code, String message, String path, String traceId) {
        return new ErrorBody(code, message, path, traceId, null);
    }

    public static ErrorBody withFieldErrors(String code, String message, String path,
                                            String traceId, List<FieldErrorEntry> fieldErrors) {
        return new ErrorBody(code, message, path, traceId, fieldErrors);
    }
}
