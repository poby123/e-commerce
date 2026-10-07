package com.ecommerce.payment.pg;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Behaves by test token, like PG sandboxes do:
 * <ul>
 *   <li>tok_approve: approved</li>
 *   <li>tok_decline: declined (CARD_DECLINED)</li>
 *   <li>tok_slow: approved after 15 s, longer than the saga's step timeout</li>
 * </ul>
 * Idempotency keys are kept in memory: enough for a mock, a real PG keeps them on its side.
 */
@Component
public class MockPaymentGateway implements PaymentGateway {

    private static final Logger log = LoggerFactory.getLogger(MockPaymentGateway.class);

    private final Map<String, Result> byIdempotencyKey = new ConcurrentHashMap<>();
    private final long slowMillis;

    public MockPaymentGateway() {
        this(15_000);
    }

    MockPaymentGateway(long slowMillis) {
        this.slowMillis = slowMillis;
    }

    @Override
    public Result approve(String idempotencyKey, long amount, String paymentToken) {
        return byIdempotencyKey.computeIfAbsent(idempotencyKey, key -> switch (paymentToken) {
            case "tok_approve" -> approved();
            case "tok_decline" -> new Declined("CARD_DECLINED");
            case "tok_slow" -> {
                sleep();
                yield approved();
            }
            default -> new Declined("INVALID_PAYMENT_TOKEN");
        });
    }

    @Override
    public void refund(String transactionId) {
        log.info("Refunded {}", transactionId);
    }

    private static Approved approved() {
        return new Approved("pg_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20));
    }

    private void sleep() {
        try {
            Thread.sleep(slowMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
