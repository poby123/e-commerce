package com.ecommerce.order.error;

import com.ecommerce.common.error.ErrorCode;
import org.springframework.http.HttpStatus;

public enum OrderErrorCode implements ErrorCode {

    PRODUCT_NOT_FOUND(HttpStatus.BAD_REQUEST, "ORDER-001", "A product in the order does not exist"),
    PRODUCT_NOT_ON_SALE(HttpStatus.BAD_REQUEST, "ORDER-002", "A product in the order is not on sale"),
    ORDER_NOT_FOUND(HttpStatus.NOT_FOUND, "ORDER-004", "Order not found"),
    DEPENDENCY_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "ORDER-005", "A required service is unavailable, try again later");

    private final HttpStatus status;
    private final String code;
    private final String message;

    OrderErrorCode(HttpStatus status, String code, String message) {
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
