package com.ecommerce.coupon.domain;

public enum DiscountType {
    /** discountValue KRW off. */
    FIXED,
    /** discountValue % off, capped by maxDiscountAmount. */
    RATE
}
