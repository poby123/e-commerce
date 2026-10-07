-- A release that arrived before its reserve (the saga timed out and compensated while the reserve was still
-- in flight). The late reserve finds this row and is rejected instead of taking stock nobody will give back.
CREATE TABLE released_orders
(
    order_id   VARCHAR(36) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (order_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;
