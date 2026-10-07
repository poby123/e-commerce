package com.ecommerce.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Transactional outbox. A message is written to the service's own `outbox` table in the same transaction as the
 * business change, so both commit or neither does. Debezium reads the table from the binlog and publishes each
 * row to Kafka: topic = aggregate_type, key = aggregate_id, headers id, type and traceparent.
 *
 * <p>traceparent is the current W3C trace context. Debezium is not traced, so without it every message would
 * start a new trace; with it, one order is one trace across HTTP, the outbox, Kafka and every service.
 */
public class Outbox {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final Supplier<String> traceparent;

    public Outbox(JdbcTemplate jdbc, ObjectMapper objectMapper, Supplier<String> traceparent) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.traceparent = traceparent;
    }

    /**
     * @param topic   destination topic
     * @param key     partition key; messages with the same key (one saga, one order) stay in order
     * @param message a record from {@code contract}; its simple class name becomes the "type" header
     * @return the message id consumers use for de-duplication
     */
    public String send(String topic, String key, Object message) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            // Outside a transaction the row could commit without the business change (or the reverse).
            throw new IllegalStateException("Outbox.send must run inside the transaction of the business change");
        }
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO outbox (id, aggregate_type, aggregate_id, type, payload, traceparent, created_at)
                VALUES (?, ?, ?, ?, ?, ?, NOW(6))
                """, id, topic, key, message.getClass().getSimpleName(), toJson(message), traceparent.get());
        return id;
    }

    private String toJson(Object message) {
        try {
            return objectMapper.writeValueAsString(message);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Cannot serialize " + message.getClass(), e);
        }
    }
}
