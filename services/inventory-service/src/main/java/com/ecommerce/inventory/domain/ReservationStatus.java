package com.ecommerce.inventory.domain;

public enum ReservationStatus {
    RESERVED,
    /** Stock returned (order failed or cancelled). */
    RELEASED
}
