-- Transactional outbox (written by com.ecommerce.messaging.Outbox, published by Debezium) and the
-- idempotent-consumer log (com.ecommerce.messaging.ProcessedMessages).
CREATE TABLE outbox
(
    id             VARCHAR(36)  NOT NULL,
    aggregate_type VARCHAR(100) NOT NULL, -- destination topic
    aggregate_id   VARCHAR(100) NOT NULL, -- partition key
    type           VARCHAR(100) NOT NULL,
    payload        JSON         NOT NULL,
    traceparent    VARCHAR(64)  NOT NULL DEFAULT '',
    created_at     DATETIME(6)  NOT NULL,
    seq            BIGINT       NOT NULL AUTO_INCREMENT, -- insertion order (several rows can share a created_at)
    PRIMARY KEY (id),
    UNIQUE KEY uk_outbox_seq (seq),
    INDEX idx_outbox_created_at (created_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE processed_messages
(
    message_id   VARCHAR(36) NOT NULL,
    processed_at DATETIME(6) NOT NULL,
    PRIMARY KEY (message_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;
