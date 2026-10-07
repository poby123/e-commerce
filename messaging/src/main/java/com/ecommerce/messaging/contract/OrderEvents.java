package com.ecommerce.messaging.contract;

public final class OrderEvents {

    private OrderEvents() {
    }

    public record OrderCompleted(String orderId, long userId, long paymentAmount) {
    }

    public record OrderCancelled(String orderId, long userId, String reason) {
    }
}
