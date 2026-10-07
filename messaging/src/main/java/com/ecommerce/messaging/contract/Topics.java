package com.ecommerce.messaging.contract;

import java.util.List;

/**
 * Commands go to the service that executes them; every saga reply goes back to order-service.
 * Events are facts other services may react to (choreography).
 */
public final class Topics {

    public static final String COUPON_COMMAND = "coupon.command";
    public static final String INVENTORY_COMMAND = "inventory.command";
    public static final String PAYMENT_COMMAND = "payment.command";
    public static final String ORDER_SAGA_REPLY = "order.saga-reply";

    public static final String USER_EVENTS = "user.events";
    public static final String ORDER_EVENTS = "order.events";

    public static final List<String> ALL = List.of(COUPON_COMMAND, INVENTORY_COMMAND, PAYMENT_COMMAND, ORDER_SAGA_REPLY,
            USER_EVENTS, ORDER_EVENTS);

    private Topics() {
    }
}
