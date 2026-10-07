package com.ecommerce.order.saga;

import com.ecommerce.order.domain.Order;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Business metrics for the saga (Grafana "Orders" row):
 * ecommerce_orders_finished_total{result,reason}, ecommerce_orders_saga_duration_seconds{result},
 * ecommerce_saga_running.
 */
@Component
public class SagaMetrics {

    private final MeterRegistry registry;

    public SagaMetrics(MeterRegistry registry, OrderSagaRepository sagas) {
        this.registry = registry;
        Gauge.builder("ecommerce.saga.running", sagas, s -> s.countByState(OrderSaga.State.RUNNING))
                .description("Sagas waiting for a participant")
                .register(registry);
    }

    void finished(Order order, OrderSaga.State result) {
        String reason = order.getFailureReason() == null ? "none" : order.getFailureReason();
        Counter.builder("ecommerce.orders.finished")
                .tag("result", result.name())
                .tag("reason", reason)
                .register(registry)
                .increment();
        Timer.builder("ecommerce.orders.saga.duration")
                .description("From order accepted to completed or cancelled")
                .tag("result", result.name())
                .publishPercentileHistogram()
                .register(registry)
                .record(Duration.between(order.getCreatedAt(), Instant.now()));
    }
}
