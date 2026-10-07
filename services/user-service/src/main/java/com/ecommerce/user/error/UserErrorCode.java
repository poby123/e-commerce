package com.ecommerce.user.error;

import com.ecommerce.common.error.ErrorCode;
import org.springframework.http.HttpStatus;

public enum UserErrorCode implements ErrorCode {

    DUPLICATE_EMAIL(HttpStatus.CONFLICT, "USER-001", "Email is already registered"),
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "USER-002", "User not found"),
    // Same code for unknown email and wrong password, so the response does not reveal which accounts exist.
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "AUTH-001", "Email or password is incorrect"),
    INVALID_REFRESH_TOKEN(HttpStatus.UNAUTHORIZED, "AUTH-002", "Refresh token is invalid or expired");

    private final HttpStatus status;
    private final String code;
    private final String message;

    UserErrorCode(HttpStatus status, String code, String message) {
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
