-- Release that overtook its reserve; see inventory-service V3 for the same idea.
CREATE TABLE released_orders
(
    order_id   VARCHAR(36) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (order_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

-- Issued to every new user on UserRegistered (choreography): 3,000 KRW off orders of 10,000 KRW or more.
INSERT INTO coupon_policies (code, name, discount_type, discount_value, max_discount_amount, min_order_amount,
                             total_quantity, issued_quantity, valid_days, created_at)
VALUES ('WELCOME', 'Welcome 3,000 KRW', 'FIXED', 3000, NULL, 10000, NULL, 0, 30, NOW(6));
