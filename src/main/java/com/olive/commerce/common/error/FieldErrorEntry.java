package com.olive.commerce.common.error;

public record FieldErrorEntry(String field, String message, Object rejectedValue) {
}
