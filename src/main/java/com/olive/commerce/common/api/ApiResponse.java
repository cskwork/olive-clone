package com.olive.commerce.common.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.olive.commerce.common.error.ErrorBody;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(
    boolean success,
    T data,
    ErrorBody error,
    PageMeta meta
) {
    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(true, data, null, null);
    }

    public static <T> ApiResponse<T> success(T data, PageMeta meta) {
        return new ApiResponse<>(true, data, null, meta);
    }

    public static <T> ApiResponse<T> failure(ErrorBody error) {
        return new ApiResponse<>(false, null, error, null);
    }
}
