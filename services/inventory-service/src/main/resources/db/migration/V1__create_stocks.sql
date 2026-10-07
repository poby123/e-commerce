CREATE TABLE stocks
(
    product_id BIGINT      NOT NULL,
    quantity   INT         NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (product_id),
    -- Last line of defence: even a buggy UPDATE cannot drive stock negative.
    CONSTRAINT ck_stocks_quantity CHECK (quantity >= 0)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE stock_reservations
(
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    order_id   VARCHAR(36) NOT NULL,
    product_id BIGINT      NOT NULL,
    quantity   INT         NOT NULL,
    status     VARCHAR(20) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_stock_reservations_order_product UNIQUE (order_id, product_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;
