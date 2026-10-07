package com.ecommerce.messaging.contract;

import java.util.List;

public final class InventoryMessages {

    private InventoryMessages() {
    }

    public record Item(long productId, int quantity) {
    }

    public record ReserveStock(String sagaId, List<Item> items) {
    }

    public record ReleaseStock(String sagaId) {
    }

    public record StockReserved(String sagaId) {
    }

    public record StockRejected(String sagaId, String reason) {
    }
}
