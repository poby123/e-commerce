package com.ecommerce.order.domain;

public enum OrderStatus {
    /** The saga is running: coupon, stock and payment are being reserved. */
    PENDING,
    COMPLETED,
    /** A step was refused or timed out; everything reserved was given back. See failureReason. */
    CANCELLED
}
