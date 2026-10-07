package com.ecommerce.inventory;

import com.ecommerce.inventory.loadtest.StockDecreaseStrategies;
import com.ecommerce.inventory.loadtest.StockDecreaseStrategies.Strategy;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.testcontainers.containers.GenericContainer;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/** Correctness before speed: every strategy must never oversell. Speed is measured with k6 (load-test/). */
@SpringBootTest(properties = {
        "spring.cloud.config.enabled=false",
        "eureka.client.enabled=false",
        "spring.kafka.listener.auto-startup=false",
        "spring.kafka.admin.auto-create=false",
        "spring.datasource.hikari.maximum-pool-size=20",
        "ecommerce.loadtest.enabled=true",
})
@Import({ReservationTest.Containers.class, StockDecreaseStrategiesTest.RedisContainer.class})
class StockDecreaseStrategiesTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class RedisContainer {
        @Bean
        @ServiceConnection(name = "redis")
        GenericContainer<?> redis() {
            return new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);
        }
    }

    private static final AtomicLong PRODUCT_IDS = new AtomicLong(900_000);

    @Autowired
    StockDecreaseStrategies strategies;

    @ParameterizedTest
    @EnumSource(Strategy.class)
    void neverOversells(Strategy strategy) throws Exception {
        long product = PRODUCT_IDS.incrementAndGet();
        strategies.reset(product, 40);
        AtomicInteger taken = new AtomicInteger();
        AtomicInteger soldOut = new AtomicInteger();
        AtomicInteger gaveUp = new AtomicInteger();

        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(40)) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    try {
                        (strategies.decrease(strategy, product) ? taken : soldOut).incrementAndGet();
                    } catch (StockDecreaseStrategies.GaveUp e) {
                        gaveUp.incrementAndGet();
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get();
            }
        }

        // Whatever the strategy, sold units and remaining stock add up to what there was.
        assertThat(taken.get() + strategies.quantity(product)).isEqualTo(40);
        assertThat(taken.get() + soldOut.get() + gaveUp.get()).isEqualTo(100);
        if (strategy != Strategy.OPTIMISTIC) {
            assertThat(taken.get()).isEqualTo(40);
        }
    }
}
