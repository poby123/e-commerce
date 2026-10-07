package com.ecommerce.order;

import com.ecommerce.messaging.IncomingMessage;
import com.ecommerce.messaging.contract.CouponMessages.CouponRejected;
import com.ecommerce.messaging.contract.CouponMessages.CouponReserved;
import com.ecommerce.messaging.contract.InventoryMessages.StockRejected;
import com.ecommerce.messaging.contract.InventoryMessages.StockReserved;
import com.ecommerce.messaging.contract.PaymentMessages.PaymentCompleted;
import com.ecommerce.messaging.contract.PaymentMessages.PaymentFailed;
import com.ecommerce.order.api.OrderDtos.Line;
import com.ecommerce.order.api.OrderDtos.PlaceOrderRequest;
import com.ecommerce.order.application.OrderService;
import com.ecommerce.order.domain.Order;
import com.ecommerce.order.domain.OrderRepository;
import com.ecommerce.order.domain.OrderStatus;
import com.ecommerce.order.saga.OrderSagaManager;
import com.ecommerce.order.saga.SagaTimeoutJob;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.support.TransactionTemplate;
import com.ecommerce.order.saga.OrderSagaRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Import(OrderSagaTest.TimeoutJob.class)
class OrderSagaTest extends OrderTestSupport {

    /** The job is disabled for the context (no background thread); tests run it explicitly at a chosen time. */
    @TestConfiguration
    static class TimeoutJob {
        @Bean
        SagaTimeoutJob sagaTimeoutJob(OrderSagaRepository sagas, OrderSagaManager manager, TransactionTemplate tx) {
            return new SagaTimeoutJob(sagas, manager, tx);
        }
    }

    @Autowired
    OrderService orderService;
    @Autowired
    OrderSagaManager manager;
    @Autowired
    OrderRepository orders;
    @Autowired
    SagaTimeoutJob timeoutJob;
    @Autowired
    ObjectMapper objectMapper;

    @Test
    void happyPathWithCoupon() throws Exception {
        String id = place(55L);

        reply(id, new CouponReserved(id, 3_000));
        reply(id, new StockReserved(id));
        assertThat(sentField(id, "Pay", "amount")).isEqualTo("9000"); // 12,000 - 3,000
        reply(id, new PaymentCompleted(id, "pg_1"));

        Order order = order(id);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.COMPLETED);
        assertThat(order.getPaymentAmount()).isEqualTo(9_000);
        assertThat(sent(id)).containsExactly(
                "coupon.command:ReserveCoupon", "inventory.command:ReserveStock", "payment.command:Pay",
                "coupon.command:ConfirmCoupon", "order.events:OrderCompleted");
    }

    @Test
    void rejectedCouponCancelsWithNothingToUndo() throws Exception {
        String id = place(55L);

        reply(id, new CouponRejected(id, "COUPON_EXPIRED"));

        assertThat(order(id).getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(order(id).getFailureReason()).isEqualTo("COUPON_EXPIRED");
        assertThat(sent(id)).containsExactly("coupon.command:ReserveCoupon", "order.events:OrderCancelled");
    }

    @Test
    void outOfStockGivesTheCouponBack() throws Exception {
        String id = place(55L);

        reply(id, new CouponReserved(id, 3_000));
        reply(id, new StockRejected(id, "OUT_OF_STOCK"));

        assertThat(order(id).getFailureReason()).isEqualTo("OUT_OF_STOCK");
        assertThat(sent(id)).endsWith("coupon.command:ReleaseCoupon", "order.events:OrderCancelled");
    }

    @Test
    void declinedPaymentReleasesStockAndCouponInReverseOrder() throws Exception {
        String id = place(55L);

        reply(id, new CouponReserved(id, 3_000));
        reply(id, new StockReserved(id));
        reply(id, new PaymentFailed(id, "CARD_DECLINED"));

        assertThat(order(id).getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(sent(id)).endsWith("inventory.command:ReleaseStock", "coupon.command:ReleaseCoupon", "order.events:OrderCancelled");
    }

    @Test
    void duplicateAndLateRepliesChangeNothing() throws Exception {
        String id = place(null);
        IncomingMessage stockReserved = message(id, new StockReserved(id));

        manager.onReply(stockReserved);
        manager.onReply(stockReserved);                      // redelivered
        reply(id, new StockReserved(id));                    // answer to a resent command
        reply(id, new PaymentFailed(id, "CARD_DECLINED"));
        reply(id, new PaymentCompleted(id, "pg_late"));      // after the saga cancelled

        assertThat(order(id).getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(sent(id)).containsExactly("inventory.command:ReserveStock", "payment.command:Pay",
                "inventory.command:ReleaseStock", "order.events:OrderCancelled");
    }

    @Test
    void silentParticipantIsRetriedThenCompensated() throws Exception {
        String id = place(55L);
        reply(id, new CouponReserved(id, 3_000));
        reply(id, new StockReserved(id));
        Instant late = Instant.now().plus(Duration.ofMinutes(1));

        timeoutJob.runAt(late);                                   // attempt 2 of 2: resend Pay
        assertThat(order(id).getStatus()).isEqualTo(OrderStatus.PENDING);
        timeoutJob.runAt(late.plus(Duration.ofMinutes(1)));       // still nothing: compensate

        assertThat(order(id).getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(order(id).getFailureReason()).isEqualTo("TIMEOUT_PAY");
        // The Pay may have gone through unseen, so it is cancelled too.
        assertThat(sent(id)).endsWith("payment.command:Pay", "payment.command:Pay",
                "payment.command:CancelPayment", "inventory.command:ReleaseStock", "coupon.command:ReleaseCoupon",
                "order.events:OrderCancelled");
    }

    @Test
    void timeoutJobIgnoresSagasThatAreNotDue() throws Exception {
        String id = place(null);
        timeoutJob.runAt(Instant.now());
        assertThat(sent(id)).containsExactly("inventory.command:ReserveStock");
    }

    private String place(Long couponId) {
        return orderService.place(42, null, new PlaceOrderRequest(List.of(new Line(1L, 1)), couponId, "tok_approve")).getOrderId();
    }

    private void reply(String sagaId, Object payload) throws Exception {
        manager.onReply(message(sagaId, payload));
    }

    private IncomingMessage message(String sagaId, Object payload) throws Exception {
        return new IncomingMessage(UUID.randomUUID().toString(), payload.getClass().getSimpleName(), sagaId,
                objectMapper.writeValueAsString(payload));
    }

    private Order order(String id) {
        return orders.findByOrderId(id).orElseThrow();
    }
}
