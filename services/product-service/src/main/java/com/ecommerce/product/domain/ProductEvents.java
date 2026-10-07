package com.ecommerce.product.domain;

import com.ecommerce.messaging.Outbox;
import com.ecommerce.messaging.contract.ProductMessages.ProductChanged;
import com.ecommerce.messaging.contract.Topics;
import org.springframework.stereotype.Component;

/**
 * Publishes the full product state (not a diff) on every change, through the outbox. A consumer that missed or
 * reordered events still converges: the latest version wins.
 */
@Component
public class ProductEvents {

    private final Outbox outbox;

    public ProductEvents(Outbox outbox) {
        this.outbox = outbox;
    }

    /** Call inside the transaction that changed the product, after flush (so the version is current). */
    public void changed(Product product) {
        outbox.send(Topics.PRODUCT_EVENTS, String.valueOf(product.getId()), new ProductChanged(
                product.getId(), product.getName(), product.getDescription(), product.getBrand(),
                product.getCategory().name(), product.getPrice(), product.getStatus().name(), product.getVersion(),
                product.getCreatedAt()));
    }
}
