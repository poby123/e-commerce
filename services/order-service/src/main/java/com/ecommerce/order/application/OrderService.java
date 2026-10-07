package com.ecommerce.order.application;

import com.ecommerce.common.error.BusinessException;
import com.ecommerce.order.api.OrderDtos.Line;
import com.ecommerce.order.client.DownstreamException;
import com.ecommerce.order.client.InventoryClient;
import com.ecommerce.order.client.ProductClient;
import com.ecommerce.order.client.ProductClient.ProductSnapshot;
import com.ecommerce.order.domain.Order;
import com.ecommerce.order.domain.OrderItem;
import com.ecommerce.order.domain.OrderRepository;
import com.ecommerce.order.error.OrderErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClientException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Places an order with synchronous calls: product-service (prices) then inventory-service (stock).
 *
 * <p>No DB transaction is open while another service is called: each local step commits on its own, so a slow
 * dependency does not hold a DB connection and row locks.
 *
 * <p><b>Known gap, kept on purpose until step 3 (Saga):</b> if inventory does not answer in time we cannot tell
 * whether it reserved the stock. The order is left PENDING and nothing reconciles it. A PENDING order may
 * therefore be holding stock forever. See OrderPlacementTest#inventoryTimeoutLeavesOrderPending.
 */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);
    private static final String ON_SALE = "ON_SALE";

    private final OrderRepository orderRepository;
    private final ProductClient productClient;
    private final InventoryClient inventoryClient;
    private final TransactionTemplate transaction;

    public OrderService(OrderRepository orderRepository, ProductClient productClient, InventoryClient inventoryClient,
                        TransactionTemplate transaction) {
        this.orderRepository = orderRepository;
        this.productClient = productClient;
        this.inventoryClient = inventoryClient;
        this.transaction = transaction;
    }

    public Order place(long userId, String idempotencyKey, List<Line> lines) {
        if (idempotencyKey != null) {
            var existing = orderRepository.findByUserIdAndIdempotencyKey(userId, idempotencyKey);
            if (existing.isPresent()) {
                return existing.get();
            }
        }

        Map<Long, Integer> quantities = mergeLines(lines);
        List<OrderItem> items = priceItems(quantities);

        Order order;
        try {
            order = transaction.execute(status -> orderRepository.save(Order.place(userId, idempotencyKey, items)));
        } catch (DataIntegrityViolationException duplicate) {
            // Two requests with the same key raced past the lookup above; the first one's order is the answer.
            return orderRepository.findByUserIdAndIdempotencyKey(userId, idempotencyKey).orElseThrow(() -> duplicate);
        }

        return reserveStock(order);
    }

    private Order reserveStock(Order order) {
        var request = new InventoryClient.ReserveRequest(order.getOrderId(), order.getItems().stream()
                .map(item -> new InventoryClient.Item(item.getProductId(), item.getQuantity()))
                .toList());
        try {
            inventoryClient.reserve(request);
        } catch (DownstreamException e) {
            if (!"INVENTORY-001".equals(e.code())) {
                throw e;
            }
            update(order, o -> o.reject("OUT_OF_STOCK"));
            throw new BusinessException(OrderErrorCode.OUT_OF_STOCK);
        } catch (RestClientException e) {
            // Timeout or 5xx: the reservation may or may not have happened. Leave the order PENDING.
            log.warn("Inventory did not confirm order {}; left PENDING", order.getOrderId(), e);
            return order;
        }
        return update(order, Order::confirm);
    }

    private Order update(Order order, Consumer<Order> change) {
        return transaction.execute(status -> {
            Order managed = orderRepository.findByOrderId(order.getOrderId()).orElseThrow();
            change.accept(managed);
            return managed;
        });
    }

    /** Same product twice in one request (e.g. added from two pages) becomes one line. */
    private static Map<Long, Integer> mergeLines(List<Line> lines) {
        return lines.stream().collect(Collectors.toMap(Line::productId, Line::quantity, Integer::sum, LinkedHashMap::new));
    }

    private List<OrderItem> priceItems(Map<Long, Integer> quantities) {
        Map<Long, ProductSnapshot> products;
        try {
            products = productClient.findAll(quantities.keySet()).stream()
                    .collect(Collectors.toMap(ProductSnapshot::id, Function.identity()));
        } catch (RestClientException e) {
            log.warn("Product lookup failed", e);
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
