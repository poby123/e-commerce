CREATE TABLE coupon_policies
(
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    code                VARCHAR(50)  NOT NULL,
    name                VARCHAR(100) NOT NULL,
    discount_type       VARCHAR(10)  NOT NULL, -- FIXED (KRW) | RATE (%)
    discount_value      BIGINT       NOT NULL,
    max_discount_amount BIGINT       NULL,     -- cap for RATE
    min_order_amount    BIGINT       NOT NULL,
    total_quantity      INT          NULL,     -- NULL = unlimited
    issued_quantity     INT          NOT NULL,
    valid_days          INT          NOT NULL, -- each issued coupon expires this many days after issue
    created_at          DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_coupon_policies_code UNIQUE (code),
    CONSTRAINT ck_coupon_policies_quantity CHECK (total_quantity IS NULL OR issued_quantity <= total_quantity)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE user_coupons
(
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    policy_id       BIGINT      NOT NULL,
    user_id         BIGINT      NOT NULL,
    status          VARCHAR(20) NOT NULL, -- ISSUED -> RESERVED -> USED, RESERVED -> ISSUED on release
    order_id        VARCHAR(36) NULL,     -- the order holding it while RESERVED / that used it
    discount_amount BIGINT      NULL,
    expires_at      DATETIME(6) NOT NULL,
    issued_at       DATETIME(6) NOT NULL,
    used_at         DATETIME(6) NULL,
    PRIMARY KEY (id),
    -- One coupon per policy per user, enforced by the database rather than a check-then-insert.
    CONSTRAINT uk_user_coupons_policy_user UNIQUE (policy_id, user_id),
    CONSTRAINT fk_user_coupons_policy FOREIGN KEY (policy_id) REFERENCES coupon_policies (id),
    INDEX idx_user_coupons_user (user_id, status)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;
