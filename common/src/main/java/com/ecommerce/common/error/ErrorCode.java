package com.ecommerce.common.error;

import org.springframework.http.HttpStatus;

/**
 * An error a service can return. Each service defines its own enum (e.g. UserErrorCode);
 * {@code code} is stable and meant for clients, {@code message} is for humans.
 */
public interface ErrorCode {

    HttpStatus status();

    String code();

    String message();
}
