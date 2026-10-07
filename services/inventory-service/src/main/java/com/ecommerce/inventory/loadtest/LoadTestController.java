package com.ecommerce.inventory.loadtest;

import com.ecommerce.inventory.loadtest.StockDecreaseStrategies.Strategy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Load-test entry points (load-test/stock-strategies.js). Exists only with ecommerce.loadtest.enabled=true, and
 * /internal/** has no gateway route.
 */
@RestController
@RequestMapping("/internal/loadtest/stocks/{productId}")
@ConditionalOnProperty("ecommerce.loadtest.enabled")
public class LoadTestController {

    @Configuration
    @ConditionalOnProperty("ecommerce.loadtest.enabled")
    static class Beans {
        @Bean
        StockDecreaseStrategies stockDecreaseStrategies(JdbcTemplate jdbc, TransactionTemplate tx, StringRedisTemplate redis) {
            return new StockDecreaseStrategies(jdbc, tx, redis);
        }
    }

    private final StockDecreaseStrategies strategies;

    public LoadTestController(StockDecreaseStrategies strategies) {
        this.strategies = strategies;
    }

    @PostMapping("/reset")
    public Map<String, Integer> reset(@PathVariable long productId, @RequestParam int quantity) {
        strategies.reset(productId, quantity);
        return Map.of("quantity", strategies.quantity(productId));
    }

    @GetMapping
    public Map<String, Integer> get(@PathVariable long productId) {
        return Map.of("quantity", strategies.quantity(productId));
    }

    /** 200 took one, 409 sold out, 503 optimistic lock gave up. */
    @PostMapping("/decrease")
    public ResponseEntity<Map<String, String>> decrease(@PathVariable long productId, @RequestParam Strategy strategy) {
        try {
            return strategies.decrease(strategy, productId)
                    ? ResponseEntity.ok(Map.of("result", "TAKEN"))
                    : ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("result", "SOLD_OUT"));
        } catch (StockDecreaseStrategies.GaveUp e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("result", "GAVE_UP"));
        }
    }
}
