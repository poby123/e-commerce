package com.ecommerce.order.domain;

public enum OrderStatus {
    /** Saved, stock not confirmed yet. Also where an order is left when inventory did not answer in time. */
    PENDING,
    CONFIRMED,
    REJECTED
}
