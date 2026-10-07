package com.ecommerce.order.application;

import com.ecommerce.common.error.BusinessException;
import com.ecommerce.order.api.OrderDtos.Line;
import com.ecommerce.order.api.OrderDtos.PlaceOrderRequest;
import com.ecommerce.order.client.DownstreamException;
import com.ecommerce.order.client.ProductCatalog;
import com.ecommerce.order.client.ProductClient.ProductSnapshot;
import com.ecommerce.order.domain.Order;
import com.ecommerce.order.domain.OrderItem;
import com.ecommerce.order.domain.OrderRepository;
import com.ecommerce.order.error.OrderErrorCode;
import com.ecommerce.order.saga.OrderSagaManager;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClientException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Accepts an order: prices it (a read, so a synchronous call is fine), then saves it together with its saga
 * and the saga's first command in one transaction. Everything that changes other services' data happens in
 * the saga (ADR-0002), which replaced step 2's synchronous inventory call (ADR-0007).
 */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);
    private static final String ON_SALE = "ON_SALE";

    private final OrderRepository orderRepository;
    private final ProductCatalog productCatalog;
    private final OrderSagaManager sagaManager;
    private final TransactionTemplate transaction;

    public OrderService(OrderRepository orderRepository, ProductCatalog productCatalog, OrderSagaManager sagaManager,
                        TransactionTemplate transaction) {
        this.orderRepository = orderRepository;
        this.productCatalog = productCatalog;
        this.sagaManager = sagaManager;
        this.transaction = transaction;
    }

    public Order place(long userId, String idempotencyKey, PlaceOrderRequest request) {
        if (idempotencyKey != null) {
            var existing = orderRepository.findByUserIdAndIdempotencyKey(userId, idempotencyKey);
            if (existing.isPresent()) {
                return existing.get();
            }
        }

        List<OrderItem> items = priceItems(mergeLines(request.items()));

        try {
            return transaction.execute(status -> {
                Order order = orderRepository.save(Order.place(userId, idempotencyKey, request.userCouponId(), items));
                sagaManager.start(order, request.paymentToken());
                return order;
            });
        } catch (DataIntegrityViolationException duplicate) {
            // Two requests with the same key raced past the lookup above; the first one's order is the answer.
            return orderRepository.findByUserIdAndIdempotencyKey(userId, idempotencyKey).orElseThrow(() -> duplicate);
        }
    }

    /** Same product twice in one request (e.g. added from two pages) becomes one line. */
    private static Map<Long, Integer> mergeLines(List<Line> lines) {
        return lines.stream().collect(Collectors.toMap(Line::productId, Line::quantity, Integer::sum, LinkedHashMap::new));
    }

    private List<OrderItem> priceItems(Map<Long, Integer> quantities) {
        Map<Long, ProductSnapshot> products;
        try {
            products = productCatalog.findAll(quantities.keySet()).stream()
                    .collect(Collectors.toMap(ProductSnapshot::id, Function.identity()));
        } catch (CallNotPermittedException | BulkheadFullException e) {
            // Circuit open or too many lookups in flight: answered without calling product-service.
            throw new BusinessException(OrderErrorCode.DEPENDENCY_UNAVAILABLE);
        } catch (RestClientException e) {
            log.warn("Product lookup failed", e);
            throw new BusinessException(OrderErrorCode.DEPENDENCY_UNAVAILABLE);
        } catch (DownstreamException e) {
            // product-service refused our request (a bug on our side, e.g. too many ids): not the client's fault.
            log.error("Product lookup rejected: {}", e.getMessage());
            throw new BusinessException(OrderErrorCode.DEPENDENCY_UNAVAILABLE);
        }
        return quantities.entrySet().stream().map(entry -> {
            ProductSnapshot product = products.get(entry.getKey());
            if (product == null) {
                throw new BusinessException(OrderErrorCode.PRODUCT_NOT_FOUND, "Product " + entry.getKey() + " does not exist");
            }
            if (!ON_SALE.equals(product.status())) {
                throw new BusinessException(OrderErrorCode.PRODUCT_NOT_ON_SALE, "Product " + product.id() + " is not on sale");
            }
            return new OrderItem(product.id(), product.name(), product.price(), entry.getValue());
        }).toList();
    }
}
