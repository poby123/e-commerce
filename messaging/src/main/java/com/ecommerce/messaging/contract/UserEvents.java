package com.ecommerce.messaging.contract;

public final class UserEvents {

    private UserEvents() {
    }

    public record UserRegistered(long userId) {
    }
}
