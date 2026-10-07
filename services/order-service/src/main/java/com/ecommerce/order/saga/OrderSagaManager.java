package com.ecommerce.order.saga;

import com.ecommerce.messaging.IncomingMessage;
import com.ecommerce.messaging.Outbox;
import com.ecommerce.messaging.ProcessedMessages;
import com.ecommerce.messaging.contract.CouponMessages.CouponRejected;
import com.ecommerce.messaging.contract.CouponMessages.CouponReserved;
import com.ecommerce.messaging.contract.InventoryMessages.StockRejected;
import com.ecommerce.messaging.contract.InventoryMessages.StockReserved;
import com.ecommerce.messaging.contract.PaymentMessages.PaymentCompleted;
import com.ecommerce.messaging.contract.PaymentMessages.PaymentFailed;
import com.ecommerce.order.domain.Order;
import com.ecommerce.order.domain.OrderRepository;
import com.ecommerce.order.saga.OrderSaga.Outgoing;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs the saga: load, let {@link OrderSaga} decide, save, write its messages to the outbox. One transaction per
 * reply, so the order's state, the saga's state and the next command commit together.
 */
@Service
public class OrderSagaManager {

    private static final Logger log = LoggerFactory.getLogger(OrderSagaManager.class);

    private final OrderSagaRepository sagas;
    private final OrderRepository orders;
    private final Outbox outbox;
    private final ProcessedMessages processedMessages;
    private final SagaProperties properties;
    private final ObjectMapper objectMapper;
    private final SagaMetrics metrics;

    public OrderSagaManager(OrderSagaRepository sagas, OrderRepository orders, Outbox outbox,
                            ProcessedMessages processedMessages, SagaProperties properties, ObjectMapper objectMapper,
                            SagaMetrics metrics) {
        this.sagas = sagas;
        this.orders = orders;
        this.outbox = outbox;
        this.processedMessages = processedMessages;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
    }

    /** Called in the transaction that inserts the order. */
    public void start(Order order, String paymentToken) {
        List<Outgoing> out = new ArrayList<>();
        sagas.save(OrderSaga.start(order, paymentToken, Instant.now(), properties.stepTimeout(), out));
        send(order.getOrderId(), out);
    }

    @Transactional
    public void onReply(IncomingMessage message) {
        if (!processedMessages.markProcessed(message.id())) {
            return;
        }
        String sagaId = message.key();
        OrderSaga saga = sagas.findById(sagaId).orElse(null);
        if (saga == null) {
            log.warn("Reply {} for unknown saga {}", message.type(), sagaId);
            return;
        }
        Order order = orders.findByOrderId(sagaId).orElseThrow();
        Instant now = Instant.now();
        List<Outgoing> out = new ArrayList<>();

        boolean applied = switch (message.type()) {
            case "CouponReserved" -> saga.onCouponReserved(order, payload(message, CouponReserved.class).discountAmount(),
                    now, properties.stepTimeout(), out);
            case "CouponRejected" -> saga.onCouponRejected(order, payload(message, CouponRejected.class).reason(), out);
            case "StockReserved" -> saga.onStockReserved(order, now, properties.stepTimeout(), out);
            case "StockRejected" -> saga.onStockRejected(order, payload(message, StockRejected.class).reason(), out);
            case "PaymentCompleted" -> saga.onPaymentCompleted(order, payload(message, PaymentCompleted.class).paymentId(), out);
            case "PaymentFailed" -> saga.onPaymentFailed(order, payload(message, PaymentFailed.class).reason(), out);
            default -> throw new IllegalArgumentException("Unknown saga reply " + message.type());
        };
        if (!applied) {
            // A reply to a resent command, or one that arrived after the saga gave up. Its effect is already
            // covered: compensation was sent when the saga cancelled.
            log.info("Ignored {} for saga {} at {}/{}", message.type(), sagaId, saga.getState(), saga.getStep());
        } else if (saga.getState() != OrderSaga.State.RUNNING) {
            finished(order, saga);
        }
        send(sagaId, out);
    }

    /** Called by {@link SagaTimeoutJob} for a saga it has locked. */
    @Transactional
    public void onTimeout(String sagaId, Instant now) {
        OrderSaga saga = sagas.findById(sagaId).orElseThrow();
        Order order = orders.findByOrderId(sagaId).orElseThrow();
        List<Outgoing> out = new ArrayList<>();
        saga.onTimeout(order, now, properties.stepTimeout(), properties.maxAttempts(), out);
        if (saga.getState() != OrderSaga.State.RUNNING) {
            finished(order, saga);
        }
        log.warn("Saga {} timed out at {}: {}", sagaId, saga.getStep(), saga.getState() == OrderSaga.State.RUNNING
                ? "resent (attempt " + saga.getAttempts() + ")" : "compensated");
        send(sagaId, out);
    }

    private void finished(Order order, OrderSaga saga) {
        metrics.finished(order, saga.getState());
        log.info("Order {} {}{}", order.getOrderId(), saga.getState(),
                order.getFailureReason() == null ? "" : " (" + order.getFailureReason() + ")");
    }

    private void send(String sagaId, List<Outgoing> out) {
        out.forEach(o -> outbox.send(o.topic(), sagaId, o.message()));
    }

    private <T> T payload(IncomingMessage message, Class<T> type) {
        return message.payload(objectMapper, type);
    }
}
