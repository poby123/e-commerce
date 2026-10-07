package com.ecommerce.search.index;

import com.ecommerce.messaging.contract.ProductMessages.ProductChanged;

import java.time.Instant;

/** One product in the search index. The product's version is not stored here: it is the document's _version. */
public record ProductDocument(long productId, String name, String description, String brand, String category,
                              long price, String status, Instant createdAt) {

    public static ProductDocument from(ProductChanged e) {
        return new ProductDocument(e.productId(), e.name(), e.description(), e.brand(), e.category(), e.price(),
                e.status(), e.createdAt());
    }
}
