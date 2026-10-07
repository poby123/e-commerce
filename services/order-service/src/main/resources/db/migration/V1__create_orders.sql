CREATE TABLE orders
(
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    order_id        VARCHAR(36) NOT NULL,
    user_id         BIGINT      NOT NULL,
    status          VARCHAR(20) NOT NULL,
    total_amount    BIGINT      NOT NULL,
    failure_reason  VARCHAR(50) NULL,
    idempotency_key VARCHAR(64) NULL,
    created_at      DATETIME(6) NOT NULL,
    updated_at      DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_orders_order_id UNIQUE (order_id),
    -- A double-clicked "order" button sends the same key twice; only one order can exist for it.
    -- Orders without a key (NULL) are not constrained.
    CONSTRAINT uk_orders_user_idempotency_key UNIQUE (user_id, idempotency_key),
    INDEX idx_orders_user_id (user_id, id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE order_items
(
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    order_pk     BIGINT       NOT NULL,
    product_id   BIGINT       NOT NULL,
    product_name VARCHAR(100) NOT NULL,
    unit_price   BIGINT       NOT NULL,
    quantity     INT          NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_order_items_order FOREIGN KEY (order_pk) REFERENCES orders (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;
