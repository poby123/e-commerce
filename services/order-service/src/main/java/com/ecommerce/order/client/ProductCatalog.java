package com.ecommerce.order.client;

import com.ecommerce.order.client.ProductClient.ProductSnapshot;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;

import java.util.Collection;
import java.util.List;

/**
 * product-service as order-service sees it, with fault isolation (application.yml, resilience4j.*):
 * <ul>
 *   <li>Bulkhead: at most N concurrent lookups; beyond that, fail at once instead of piling up on a slow
 *       product-service.</li>
 *   <li>Circuit breaker: when most recent calls fail or are slow, stop calling for a while and fail fast;
 *       then let a few calls through to test recovery.</li>
 *   <li>Retry: one more try on 5xx/timeouts. Safe because the lookup is a GET.</li>
 * </ul>
 * There is no fallback on purpose: ordering at a cached, possibly stale price is worse than "try again".
 * Resilience4j nests them Retry(CircuitBreaker(Bulkhead(call))).
 */
@Component
public class ProductCatalog {

    private final ProductClient productClient;

    public ProductCatalog(ProductClient productClient) {
        this.productClient = productClient;
    }

    @Retry(name = "product")
    @CircuitBreaker(name = "product")
    @Bulkhead(name = "product")
    public List<ProductSnapshot> findAll(Collection<Long> ids) {
        try {
            return productClient.findAll(ids);
        } catch (IllegalStateException e) {
            // The load balancer found no instance (all evicted from Eureka). Same as a connection failure, so the
            // circuit breaker counts it and the caller answers 503 rather than an unhandled 500.
            throw new ResourceAccessException("product-service: " + e.getMessage());
        }
    }
}
