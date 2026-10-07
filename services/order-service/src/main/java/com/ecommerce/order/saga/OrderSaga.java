package com.ecommerce.order.saga;

import com.ecommerce.messaging.contract.CouponMessages.ConfirmCoupon;
import com.ecommerce.messaging.contract.CouponMessages.ReleaseCoupon;
import com.ecommerce.messaging.contract.CouponMessages.ReserveCoupon;
import com.ecommerce.messaging.contract.InventoryMessages.Item;
import com.ecommerce.messaging.contract.InventoryMessages.ReleaseStock;
import com.ecommerce.messaging.contract.InventoryMessages.ReserveStock;
import com.ecommerce.messaging.contract.OrderEvents.OrderCancelled;
import com.ecommerce.messaging.contract.OrderEvents.OrderCompleted;
import com.ecommerce.messaging.contract.PaymentMessages.CancelPayment;
import com.ecommerce.messaging.contract.PaymentMessages.Pay;
import com.ecommerce.messaging.contract.Topics;
import com.ecommerce.order.domain.Order;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * The order saga's state machine (orchestration, ADR-0002). It only decides; {@link OrderSagaManager} persists
 * the result and writes the returned messages to the outbox in the same transaction.
 *
 * <pre>
 * RESERVE_COUPON --CouponReserved--> RESERVE_STOCK --StockReserved--> PAY --PaymentCompleted--> COMPLETED
 *       |                                  |                            |
 *  CouponRejected                    StockRejected                PaymentFailed
 *       v                                  v                            v
 *   CANCELLED                 release coupon, CANCELLED   release stock + coupon, CANCELLED
 * </pre>
 * Steps run in order of "likely to fail and cheap to undo" first; payment, the hardest to undo, last.
 * Compensations are sent without waiting for a reply: they are idempotent, and the outbox plus consumer
 * retries (then a DLT) deliver them.
 */
@Entity
@Table(name = "order_sagas")
public class OrderSaga {

    public enum Step { RESERVE_COUPON, RESERVE_STOCK, PAY, DONE }

    public enum State { RUNNING, COMPLETED, CANCELLED }

    /** A message to write to the outbox. */
    public record Outgoing(String topic, Object message) {
    }

    @Id
    @Column(name = "saga_id", length = 36)
    private String sagaId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Step step;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private State state;

    @Column(name = "coupon_reserved", nullable = false)
    private boolean couponReserved;

    @Column(name = "payment_token", nullable = false, length = 50)
    private String paymentToken;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "deadline_at")
    private Instant deadlineAt;

    @Version
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected OrderSaga() {
    }

    public static OrderSaga start(Order order, String paymentToken, Instant now, Duration stepTimeout, List<Outgoing> out) {
        OrderSaga saga = new OrderSaga();
        saga.sagaId = order.getOrderId();
        saga.state = State.RUNNING;
        saga.paymentToken = paymentToken;
        saga.moveTo(order.getUserCouponId() != null ? Step.RESERVE_COUPON : Step.RESERVE_STOCK, order, now, stepTimeout, out);
        return saga;
    }

    // ---- replies. Each returns false when the reply does not belong to the current step (late or duplicate).

    public boolean onCouponReserved(Order order, long discount, Instant now, Duration timeout, List<Outgoing> out) {
        if (!awaiting(Step.RESERVE_COUPON)) {
            return false;
        }
        couponReserved = true;
        order.applyDiscount(discount);
        moveTo(Step.RESERVE_STOCK, order, now, timeout, out);
        return true;
    }

    public boolean onCouponRejected(Order order, String reason, List<Outgoing> out) {
        if (!awaiting(Step.RESERVE_COUPON)) {
            return false;
        }
        cancel(order, reason, out);
        return true;
    }

    public boolean onStockReserved(Order order, Instant now, Duration timeout, List<Outgoing> out) {
        if (!awaiting(Step.RESERVE_STOCK)) {
            return false;
        }
        moveTo(Step.PAY, order, now, timeout, out);
        return true;
    }

    public boolean onStockRejected(Order order, String reason, List<Outgoing> out) {
        if (!awaiting(Step.RESERVE_STOCK)) {
            return false;
        }
        releaseCoupon(order, out);
        cancel(order, reason, out);
        return true;
    }

    public boolean onPaymentCompleted(Order order, String paymentId, List<Outgoing> out) {
        if (!awaiting(Step.PAY)) {
            return false;
        }
        order.complete(paymentId);
        if (couponReserved) {
            out.add(new Outgoing(Topics.COUPON_COMMAND, new ConfirmCoupon(sagaId, order.getUserCouponId())));
        }
        out.add(new Outgoing(Topics.ORDER_EVENTS, new OrderCompleted(sagaId, order.getUserId(), order.getPaymentAmount())));
        finish(State.COMPLETED);
        return true;
    }

    public boolean onPaymentFailed(Order order, String reason, List<Outgoing> out) {
        if (!awaiting(Step.PAY)) {
            return false;
        }
        out.add(new Outgoing(Topics.INVENTORY_COMMAND, new ReleaseStock(sagaId)));
        releaseCoupon(order, out);
        cancel(order, reason, out);
        return true;
    }

    /**
     * No reply before the deadline. Resend (receivers are idempotent per saga id) until maxAttempts; then give
     * up and compensate. The step that timed out may have succeeded without us hearing about it, so it is
     * compensated too: a late PaymentCompleted is answered by CancelPayment, a late StockReserved by ReleaseStock.
     */
    public void onTimeout(Order order, Instant now, Duration timeout, int maxAttempts, List<Outgoing> out) {
        if (state != State.RUNNING) {
            return;
        }
        if (attempts < maxAttempts) {
            attempts++;
            deadlineAt = now.plus(timeout);
            out.add(commandFor(step, order));
            return;
        }
        if (step == Step.PAY) {
            out.add(new Outgoing(Topics.PAYMENT_COMMAND, new CancelPayment(sagaId)));
        }
        if (step == Step.PAY || step == Step.RESERVE_STOCK) {
            out.add(new Outgoing(Topics.INVENTORY_COMMAND, new ReleaseStock(sagaId)));
        }
        if (couponReserved || step == Step.RESERVE_COUPON) {
            out.add(new Outgoing(Topics.COUPON_COMMAND, new ReleaseCoupon(sagaId, order.getUserCouponId())));
        }
        cancel(order, "TIMEOUT_" + step.name(), out);
    }

    private void moveTo(Step next, Order order, Instant now, Duration timeout, List<Outgoing> out) {
        step = next;
        attempts = 1;
        deadlineAt = now.plus(timeout);
        out.add(commandFor(next, order));
    }

    private Outgoing commandFor(Step step, Order order) {
        return switch (step) {
            case RESERVE_COUPON -> new Outgoing(Topics.COUPON_COMMAND,
                    new ReserveCoupon(sagaId, order.getUserId(), order.getUserCouponId(), order.getTotalAmount()));
            case RESERVE_STOCK -> new Outgoing(Topics.INVENTORY_COMMAND, new ReserveStock(sagaId,
                    order.getItems().stream().map(i -> new Item(i.getProductId(), i.getQuantity())).toList()));
            case PAY -> new Outgoing(Topics.PAYMENT_COMMAND, new Pay(sagaId, order.getUserId(), order.amountToPay(), paymentToken));
            case DONE -> throw new IllegalStateException("No command for DONE");
        };
    }

    private void releaseCoupon(Order order, List<Outgoing> out) {
        if (couponReserved) {
            out.add(new Outgoing(Topics.COUPON_COMMAND, new ReleaseCoupon(sagaId, order.getUserCouponId())));
        }
    }

    private void cancel(Order order, String reason, List<Outgoing> out) {
        order.cancel(reason);
        out.add(new Outgoing(Topics.ORDER_EVENTS, new OrderCancelled(sagaId, order.getUserId(), reason)));
        finish(State.CANCELLED);
    }

    private void finish(State end) {
        state = end;
        step = Step.DONE;
        deadlineAt = null;
    }

    private boolean awaiting(Step expected) {
        return state == State.RUNNING && step == expected;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public String getSagaId() {
        return sagaId;
    }

    public Step getStep() {
        return step;
    }

    public State getState() {
        return state;
    }

    public int getAttempts() {
        return attempts;
    }
}
