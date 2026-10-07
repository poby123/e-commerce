package com.ecommerce.gateway.resilience;

import com.ecommerce.common.error.ErrorCode;
import org.springframework.http.HttpStatus;

public enum GatewayErrorCode implements ErrorCode {

    SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "GATEWAY-503", "The service is temporarily unavailable, try again shortly");

    private final HttpStatus status;
    private final String code;
    private final String message;

    GatewayErrorCode(HttpStatus status, String code, String message) {
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
