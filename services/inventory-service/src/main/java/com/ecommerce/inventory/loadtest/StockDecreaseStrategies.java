package com.ecommerce.inventory.loadtest;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Four ways to take one unit of a hot product, compared under load in step 4 (docs/adr/0009).
 * Each returns true when it took the unit, false when sold out; anything else is an exception.
 */
public class StockDecreaseStrategies {

    public enum Strategy { CONDITIONAL, PESSIMISTIC, OPTIMISTIC, REDIS }

    /** Thrown when optimistic locking gave up: the request failed although stock may be left. */
    public static class GaveUp extends RuntimeException {
        GaveUp() {
            super("optimistic lock: too many conflicts", null, false, false);
        }
    }

    private static final int OPTIMISTIC_MAX_ATTEMPTS = 30;

    /** Check and decrement atomically in Redis. -1: key missing (not initialised). */
    private static final RedisScript<Long> TAKE_ONE = RedisScript.of("""
            local stock = tonumber(redis.call('GET', KEYS[1]))
            if stock == nil then return -1 end
            if stock < 1 then return 0 end
            redis.call('DECR', KEYS[1])
            return 1
            """, Long.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final StringRedisTemplate redis;

    public StockDecreaseStrategies(JdbcTemplate jdbc, TransactionTemplate transaction, StringRedisTemplate redis) {
        this.jdbc = jdbc;
        this.transaction = transaction;
        this.redis = redis;
    }

    public boolean decrease(Strategy strategy, long productId) {
        return switch (strategy) {
            case CONDITIONAL -> conditional(productId);
            case PESSIMISTIC -> pessimistic(productId);
            case OPTIMISTIC -> optimistic(productId);
            case REDIS -> redisGate(productId);
        };
    }

    public void reset(long productId, int quantity) {
        jdbc.update("""
                INSERT INTO stocks (product_id, quantity, updated_at, version) VALUES (?, ?, NOW(6), 0)
                ON DUPLICATE KEY UPDATE quantity = ?, version = 0, updated_at = NOW(6)
                """, productId, quantity, quantity);
        redis.opsForValue().set(redisKey(productId), String.valueOf(quantity));
    }

    public int quantity(long productId) {
        return jdbc.queryForObject("SELECT quantity FROM stocks WHERE product_id = ?", Integer.class, productId);
    }

    /** 1 statement: the row lock is held only for the UPDATE itself. (The strategy the saga uses.) */
    private boolean conditional(long productId) {
        return Boolean.TRUE.equals(transaction.execute(s -> jdbc.update(
                "UPDATE stocks SET quantity = quantity - 1, updated_at = NOW(6) WHERE product_id = ? AND quantity >= 1",
                productId) == 1));
    }

    /** SELECT ... FOR UPDATE, then UPDATE: the lock is held across two round trips. */
    private boolean pessimistic(long productId) {
        return Boolean.TRUE.equals(transaction.execute(s -> {
            Integer quantity = jdbc.queryForObject("SELECT quantity FROM stocks WHERE product_id = ? FOR UPDATE", Integer.class, productId);
            if (quantity == null || quantity < 1) {
                return false;
            }
            jdbc.update("UPDATE stocks SET quantity = ?, updated_at = NOW(6) WHERE product_id = ?", quantity - 1, productId);
            return true;
        }));
    }

    /** Read without a lock, write only if the version did not change; on conflict re-read and retry. */
    private boolean optimistic(long productId) {
        for (int attempt = 0; attempt < OPTIMISTIC_MAX_ATTEMPTS; attempt++) {
            var row = jdbc.queryForMap("SELECT quantity, version FROM stocks WHERE product_id = ?", productId);
            int quantity = ((Number) row.get("quantity")).intValue();
            long version = ((Number) row.get("version")).longValue();
            if (quantity < 1) {
                return false;
            }
            int updated = jdbc.update("UPDATE stocks SET quantity = ?, version = version + 1, updated_at = NOW(6) WHERE product_id = ? AND version = ?",
                    quantity - 1, productId, version);
            if (updated == 1) {
                return true;
            }
            backoff(attempt);
        }
        throw new GaveUp();
    }

    /**
     * Redis decides who gets a unit; only winners touch MySQL. Once sold out, every further request is answered
     * by Redis alone. If the DB write fails, the unit is put back in Redis.
     */
    private boolean redisGate(long productId) {
        Long taken = redis.execute(TAKE_ONE, List.of(redisKey(productId)));
        if (taken == null || taken < 0) {
            throw new IllegalStateException("Redis stock for " + productId + " is not initialised");
        }
        if (taken == 0) {
            return false;
        }
        try {
            if (conditional(productId)) {
                return true;
            }
            throw new IllegalStateException("Redis and MySQL disagree for product " + productId);
        } catch (RuntimeException e) {
            redis.opsForValue().increment(redisKey(productId));
            throw e;
        }
    }

    private static void backoff(int attempt) {
        try {
            Thread.sleep(ThreadLocalRandom.current().nextInt(1, 5 + attempt * 2));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String redisKey(long productId) {
        return "loadtest:stock:" + productId;
    }
}
