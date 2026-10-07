CREATE TABLE payments
(
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    order_id       VARCHAR(36)  NOT NULL,
    user_id        BIGINT       NULL,
    amount         BIGINT       NOT NULL,
    status         VARCHAR(20)  NOT NULL, -- APPROVED | DECLINED | CANCELLED (refunded, or cancelled before Pay arrived)
    pg_transaction VARCHAR(64)  NULL,
    failure_reason VARCHAR(50)  NULL,
    created_at     DATETIME(6)  NOT NULL,
    updated_at     DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    -- One payment per order: a redelivered Pay finds this row and replays its result.
    CONSTRAINT uk_payments_order_id UNIQUE (order_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;
