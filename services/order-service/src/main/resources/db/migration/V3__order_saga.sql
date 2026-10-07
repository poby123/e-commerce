-- Step 3: orders are completed by OrderSaga instead of a synchronous call.
UPDATE orders SET status = 'COMPLETED' WHERE status = 'CONFIRMED';
UPDATE orders SET status = 'CANCELLED' WHERE status = 'REJECTED';

ALTER TABLE orders
    ADD COLUMN user_coupon_id  BIGINT      NULL AFTER total_amount,
    ADD COLUMN discount_amount BIGINT      NOT NULL DEFAULT 0 AFTER user_coupon_id,
    ADD COLUMN payment_amount  BIGINT      NULL AFTER discount_amount,
    ADD COLUMN payment_id      VARCHAR(64) NULL AFTER payment_amount;

CREATE TABLE order_sagas
(
    saga_id         VARCHAR(36) NOT NULL, -- = orders.order_id
    step            VARCHAR(20) NOT NULL, -- RESERVE_COUPON | RESERVE_STOCK | PAY | DONE
    state           VARCHAR(20) NOT NULL, -- RUNNING | COMPLETED | CANCELLED
    coupon_reserved BOOLEAN     NOT NULL,
    payment_token   VARCHAR(50) NOT NULL, -- PG test token, needed to resend Pay; never a card number
    attempts        INT         NOT NULL, -- sends of the current step's command
    deadline_at     DATETIME(6) NULL,     -- no reply by then: resend or compensate
    version         BIGINT      NOT NULL, -- optimistic lock: a reply and the timeout job can race
    created_at      DATETIME(6) NOT NULL,
    updated_at      DATETIME(6) NOT NULL,
    PRIMARY KEY (saga_id),
    INDEX idx_order_sagas_due (state, deadline_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;
