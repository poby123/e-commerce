-- Used only by the optimistic-lock strategy in the step 4 comparison (loadtest/StockDecreaseStrategies).
ALTER TABLE stocks ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
