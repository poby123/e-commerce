package com.ecommerce.common.error;

import org.springframework.http.HttpStatus;

public enum CommonErrorCode implements ErrorCode {

    INVALID_INPUT(HttpStatus.BAD_REQUEST, "COMMON-400", "Invalid request"),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "COMMON-401", "Authentication required"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "COMMON-403", "Access denied"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "COMMON-404", "Resource not found"),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "COMMON-405", "Method not allowed"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "COMMON-500", "Internal server error");

    private final HttpStatus status;
    private final String code;
    private final String message;

    CommonErrorCode(HttpStatus status, String code, String message) {
        this.status = status;
        this.code = code;
        this.message = message;
    }

    @Override
    public HttpStatus status() {
        return status;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public String message() {
        return message;
    }
}
