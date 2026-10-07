package com.ecommerce.coupon.error;

import com.ecommerce.common.error.ErrorCode;
import org.springframework.http.HttpStatus;

public enum CouponErrorCode implements ErrorCode {

    POLICY_NOT_FOUND(HttpStatus.NOT_FOUND, "COUPON-001", "Coupon policy not found"),
    ALREADY_ISSUED(HttpStatus.CONFLICT, "COUPON-002", "This coupon was already issued to you"),
    SOLD_OUT(HttpStatus.CONFLICT, "COUPON-003", "All coupons of this policy have been issued"),
    DUPLICATE_CODE(HttpStatus.CONFLICT, "COUPON-004", "A policy with this code already exists"),
    INVALID_POLICY(HttpStatus.BAD_REQUEST, "COUPON-005", "Invalid coupon policy"),
    WRONG_ISSUE_PATH(HttpStatus.BAD_REQUEST, "COUPON-006", "First-come policies are claimed, other policies are issued"),
    CLAIM_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "COUPON-007", "Could not record the claim, try again");

    private final HttpStatus status;
    private final String code;
    private final String message;

    CouponErrorCode(HttpStatus status, String code, String message) {
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
