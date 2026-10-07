package com.ecommerce.messaging.contract;

public final class PaymentMessages {

    private PaymentMessages() {
    }

    /** paymentToken stands for a card tokenized by the PG on the client side; no card number reaches us. */
    public record Pay(String sagaId, long userId, long amount, String paymentToken) {
    }

    /** Refund if paid; if the Pay has not arrived yet, make sure it is declined when it does. */
    public record CancelPayment(String sagaId) {
    }

    public record PaymentCompleted(String sagaId, String paymentId) {
    }

    public record PaymentFailed(String sagaId, String reason) {
    }
}
