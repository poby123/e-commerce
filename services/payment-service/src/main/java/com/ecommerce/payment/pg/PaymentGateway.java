package com.ecommerce.payment.pg;

/**
 * The external PG. A real adapter (e.g. Toss Payments) implements the same interface; the idempotency key makes
 * a retried approve return the first result instead of charging twice.
 */
public interface PaymentGateway {

    sealed interface Result permits Approved, Declined {
    }

    record Approved(String transactionId) implements Result {
    }

    record Declined(String reason) implements Result {
    }

    Result approve(String idempotencyKey, long amount, String paymentToken);

    void refund(String transactionId);
}
