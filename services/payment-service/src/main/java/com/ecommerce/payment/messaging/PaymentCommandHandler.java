package com.ecommerce.payment.messaging;

import com.ecommerce.messaging.IncomingMessage;
import com.ecommerce.messaging.Outbox;
import com.ecommerce.messaging.ProcessedMessages;
import com.ecommerce.messaging.contract.PaymentMessages.CancelPayment;
import com.ecommerce.messaging.contract.PaymentMessages.Pay;
import com.ecommerce.messaging.contract.PaymentMessages.PaymentCompleted;
import com.ecommerce.messaging.contract.PaymentMessages.PaymentFailed;
import com.ecommerce.messaging.contract.Topics;
import com.ecommerce.payment.domain.Payments;
import com.ecommerce.payment.domain.Payments.Payment;
import com.ecommerce.payment.domain.Payments.Status;
import com.ecommerce.payment.pg.PaymentGateway;
import com.ecommerce.payment.pg.PaymentGateway.Approved;
import com.ecommerce.payment.pg.PaymentGateway.Declined;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;

/**
 * The PG is outside our database, so the outbox cannot cover it. Instead:
 * <ul>
 *   <li>The PG call happens outside any DB transaction (it may take seconds).</li>
 *   <li>The order id is the PG idempotency key: a redelivered or retried Pay gets the first result, not a
 *       second charge.</li>
 *   <li>One payments row per order. A CancelPayment that arrives before its Pay leaves a CANCELLED row, and the
 *       late Pay is refused without calling the PG.</li>
 * </ul>
 */
@Component
public class PaymentCommandHandler {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(PaymentCommandHandler.class);

    private final PaymentGateway paymentGateway;
    private final Payments payments;
    private final ProcessedMessages processedMessages;
    private final Outbox outbox;
    private final TransactionTemplate transaction;
    private final ObjectMapper objectMapper;

    public PaymentCommandHandler(PaymentGateway paymentGateway, Payments payments, ProcessedMessages processedMessages,
                                 Outbox outbox, TransactionTemplate transaction, ObjectMapper objectMapper) {
        this.paymentGateway = paymentGateway;
        this.payments = payments;
        this.processedMessages = processedMessages;
        this.outbox = outbox;
        this.transaction = transaction;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = Topics.PAYMENT_COMMAND)
    public void onMessage(ConsumerRecord<String, String> record) {
        handle(IncomingMessage.from(record));
    }

    public void handle(IncomingMessage message) {
        switch (message.type()) {
            case "Pay" -> pay(message, message.payload(objectMapper, Pay.class));
            case "CancelPayment" -> cancel(message, message.payload(objectMapper, CancelPayment.class));
            default -> throw new IllegalArgumentException("Unknown payment command " + message.type());
        }
    }

    private void pay(IncomingMessage message, Pay command) {
        Optional<Payment> existing = payments.find(command.sagaId());
        if (existing.isPresent()) {
            transaction.executeWithoutResult(status -> {
                if (processedMessages.markProcessed(message.id())) {
                    reply(existing.get());
                }
            });
            return;
        }

        PaymentGateway.Result result = paymentGateway.approve(command.sagaId(), command.amount(), command.paymentToken());

        String refund = transaction.execute(status -> {
            if (!processedMessages.markProcessed(message.id())) {
                return null;
            }
            boolean inserted = switch (result) {
                case Approved a -> payments.insert(command.sagaId(), command.userId(), command.amount(), Status.APPROVED, a.transactionId(), null);
                case Declined d -> payments.insert(command.sagaId(), command.userId(), command.amount(), Status.DECLINED, null, d.reason());
            };
            Payment payment = payments.find(command.sagaId()).orElseThrow();
            reply(payment);
            // Cancelled while the PG was approving: the money must go back.
            return !inserted && payment.status() == Status.CANCELLED && result instanceof Approved a ? a.transactionId() : null;
        });
        if (refund != null) {
            paymentGateway.refund(refund);
        }
    }

    /** Compensation, no reply. Refunds an approved payment, or blocks a Pay that has not arrived yet. */
    private void cancel(IncomingMessage message, CancelPayment command) {
        String refund = transaction.execute(status -> {
            if (!processedMessages.markProcessed(message.id())) {
                return null;
            }
            Optional<Payment> payment = payments.find(command.sagaId());
            if (payment.isEmpty()) {
                payments.insert(command.sagaId(), null, 0, Status.CANCELLED, null, "CANCELLED_BEFORE_PAY");
                return null;
            }
            return payments.cancelApproved(command.sagaId()) ? payment.get().pgTransaction() : null;
        });
        // After commit; a crash here loses the refund. A real integration would keep REFUND_PENDING and retry.
        if (refund != null) {
            paymentGateway.refund(refund);
        }
    }

    private void reply(Payment payment) {
        log.info("Payment for order {}: {}{}", payment.orderId(), payment.status(),
                payment.failureReason() == null ? "" : " (" + payment.failureReason() + ")");
        Object reply = payment.status() == Status.APPROVED
                ? new PaymentCompleted(payment.orderId(), payment.pgTransaction())
                : new PaymentFailed(payment.orderId(), payment.status() == Status.CANCELLED ? "CANCELLED" : payment.failureReason());
        outbox.send(Topics.ORDER_SAGA_REPLY, payment.orderId(), reply);
    }
}
