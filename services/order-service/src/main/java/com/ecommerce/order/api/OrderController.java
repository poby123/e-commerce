package com.ecommerce.order.api;

import com.ecommerce.common.error.BusinessException;
import com.ecommerce.common.error.CommonErrorCode;
import com.ecommerce.common.web.CurrentUserId;
import com.ecommerce.order.api.OrderDtos.OrderResponse;
import com.ecommerce.order.api.OrderDtos.OrderSummary;
import com.ecommerce.order.api.OrderDtos.PlaceOrderRequest;
import com.ecommerce.order.application.OrderService;
import com.ecommerce.order.domain.Order;
import com.ecommerce.order.domain.OrderRepository;
import com.ecommerce.order.error.OrderErrorCode;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orderService;
    private final OrderRepository orderRepository;

    public OrderController(OrderService orderService, OrderRepository orderRepository) {
        this.orderService = orderService;
        this.orderRepository = orderRepository;
    }

    /**
     * 202: the order is accepted and its saga started. The outcome (COMPLETED or CANCELLED with a reason)
     * arrives asynchronously; the client polls GET /api/orders/{orderId}.
     */
    @PostMapping
    public ResponseEntity<OrderResponse> place(@CurrentUserId Long userId,
                                               @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
                                               @Valid @RequestBody PlaceOrderRequest request) {
        if (idempotencyKey != null && (idempotencyKey.isBlank() || idempotencyKey.length() > 64)) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT, "Idempotency-Key must be 1-64 characters");
        }
        Order order = orderService.place(userId, idempotencyKey, request);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(OrderResponse.from(order));
    }

    @GetMapping("/{orderId}")
    @Transactional(readOnly = true)
    public OrderResponse get(@CurrentUserId Long userId, @PathVariable String orderId) {
        return orderRepository.findByOrderId(orderId)
                // Someone else's order answers 404, not 403: whether an order id exists is not revealed.
                .filter(order -> order.getUserId().equals(userId))
                .map(OrderResponse::from)
                .orElseThrow(() -> new BusinessException(OrderErrorCode.ORDER_NOT_FOUND));
    }

    @GetMapping
    @Transactional(readOnly = true)
    public Page<OrderSummary> list(@CurrentUserId Long userId,
                                   @PageableDefault(size = 20, sort = "id", direction = Sort.Direction.DESC) Pageable pageable) {
        return orderRepository.findByUserId(userId, pageable).map(OrderSummary::from);
    }
}
