package com.ecommerce.inventory.error;

import com.ecommerce.common.error.ErrorCode;
import org.springframework.http.HttpStatus;

public enum InventoryErrorCode implements ErrorCode {

    INSUFFICIENT_STOCK(HttpStatus.CONFLICT, "INVENTORY-001", "Not enough stock"),
    STOCK_NOT_FOUND(HttpStatus.NOT_FOUND, "INVENTORY-002", "No stock registered for this product"),
    DUPLICATE_ITEM(HttpStatus.BAD_REQUEST, "INVENTORY-003", "The same product appears twice in one reservation"),
    RESERVATION_RELEASED(HttpStatus.CONFLICT, "INVENTORY-004", "This order's reservation was already released");

    private final HttpStatus status;
    private final String code;
    private final String message;

    InventoryErrorCode(HttpStatus status, String code, String message) {
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
