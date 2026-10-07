package com.ecommerce.search.index;

import com.ecommerce.messaging.IncomingMessage;
import com.ecommerce.messaging.contract.ProductMessages.ProductChanged;
import com.ecommerce.messaging.contract.Topics;
import com.ecommerce.search.embedding.Embedder;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Keeps the read model in step with product-service. No processed-messages table: the product version is the
 * Elasticsearch external version, so a redelivered or reordered event is refused by the index itself.
 */
@Component
public class ProductEventListener implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ProductEventListener.class);

    private final ProductIndex productIndex;
    private final ObjectMapper objectMapper;
    private final Embedder embedder;

    public ProductEventListener(ProductIndex productIndex, ObjectMapper objectMapper, Embedder embedder) {
        this.productIndex = productIndex;
        this.objectMapper = objectMapper;
        this.embedder = embedder;
    }

    @Override
    public void run(ApplicationArguments args) throws IOException {
        productIndex.ensureExists();
    }

    @KafkaListener(topics = Topics.PRODUCT_EVENTS)
    public void onMessage(ConsumerRecord<String, String> record) throws IOException {
        handle(IncomingMessage.from(record));
    }

    public void handle(IncomingMessage message) throws IOException {
        if (!"ProductChanged".equals(message.type())) {
            throw new IllegalArgumentException("Unknown product event " + message.type());
        }
        ProductChanged event = message.payload(objectMapper, ProductChanged.class);
        if (!productIndex.write(ProductDocument.from(event, embedder), event.version())) {
            log.debug("Skipped product {} v{}: index already has it or newer", event.productId(), event.version());
        }
    }
}
