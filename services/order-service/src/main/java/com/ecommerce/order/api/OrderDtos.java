package com.ecommerce.order.api;

import com.ecommerce.order.domain.Order;
import com.ecommerce.order.domain.OrderItem;
import com.ecommerce.order.domain.OrderStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

public final class OrderDtos {

    private OrderDtos() {
    }

    public record PlaceOrderRequest(@NotEmpty @Size(max = 50) List<@Valid Line> items) {
    }

    public record Line(@NotNull Long productId, @Positive @Max(100) int quantity) {
    }

    public record OrderResponse(String orderId, OrderStatus status, long totalAmount, String failureReason,
                                List<ItemResponse> items, Instant createdAt) {

        public static OrderResponse from(Order order) {
            return new OrderResponse(order.getOrderId(), order.getStatus(), order.getTotalAmount(), order.getFailureReason(),
                    order.getItems().stream().map(ItemResponse::from).toList(), order.getCreatedAt());
        }
    }

    public record ItemResponse(Long productId, String productName, long unitPrice, int quantity, long lineAmount) {

        static ItemResponse from(OrderItem item) {
            return new ItemResponse(item.getProductId(), item.getProductName(), item.getUnitPrice(), item.getQuantity(),
                    item.getLineAmount());
        }
    }

    public record OrderSummary(String orderId, OrderStatus status, long totalAmount, Instant createdAt) {

        public static OrderSummary from(Order order) {
            return new OrderSummary(order.getOrderId(), order.getStatus(), order.getTotalAmount(), order.getCreatedAt());
        }
    }
}
