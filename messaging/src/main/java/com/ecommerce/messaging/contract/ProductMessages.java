package com.ecommerce.messaging.contract;

import java.time.Instant;

public final class ProductMessages {

    private ProductMessages() {
    }

    /** Full state of a product after a change. version only goes up; consumers keep the highest they have seen. */
    public record ProductChanged(long productId, String name, String description, String brand, String category,
                                 long price, String status, long version, Instant createdAt) {
    }
}
