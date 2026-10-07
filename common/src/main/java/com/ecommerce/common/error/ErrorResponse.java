package com.ecommerce.common.error;

import java.time.Instant;
import java.util.List;

/** The single error body every service (and the gateway) returns. */
public record ErrorResponse(String code, String message, List<FieldError> errors, Instant timestamp) {

    public record FieldError(String field, String reason) {
    }

    public static ErrorResponse of(ErrorCode errorCode) {
        return new ErrorResponse(errorCode.code(), errorCode.message(), List.of(), Instant.now());
    }

    public static ErrorResponse of(ErrorCode errorCode, String message) {
        return new ErrorResponse(errorCode.code(), message, List.of(), Instant.now());
    }

    public static ErrorResponse of(ErrorCode errorCode, List<FieldError> errors) {
        return new ErrorResponse(errorCode.code(), errorCode.message(), errors, Instant.now());
    }
}
