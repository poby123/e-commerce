package com.ecommerce.messaging;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Idempotent consumer guard. Kafka and the outbox deliver at least once, so the same message can arrive again
 * (relay restart, consumer rebalance before the offset commit). Recording the id in the same transaction as the
 * handler's changes makes a redelivery a no-op.
 */
public class ProcessedMessages {

    private final JdbcTemplate jdbc;

    public ProcessedMessages(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** @return true the first time this id is seen; false for a duplicate (the caller skips it). */
    public boolean markProcessed(String messageId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("markProcessed must run inside the handler's transaction");
        }
        // INSERT IGNORE: a duplicate key is not an error, it affects 0 rows.
        return jdbc.update("INSERT IGNORE INTO processed_messages (message_id, processed_at) VALUES (?, NOW(6))", messageId) == 1;
    }
}
