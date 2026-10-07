package com.ecommerce.order.client;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;

import java.util.List;

@HttpExchange("/internal/reservations")
public interface InventoryClient {

    record Item(Long productId, int quantity) {
    }

    record ReserveRequest(String orderId, List<Item> items) {
    }

    record ReservationResponse(String orderId, String status, List<Item> items) {
    }

    @PostExchange
    ReservationResponse reserve(@RequestBody ReserveRequest request);

    @PostExchange("/{orderId}/release")
    void release(@PathVariable String orderId);
}
