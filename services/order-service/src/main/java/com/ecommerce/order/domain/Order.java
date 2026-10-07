package com.ecommerce.order.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "orders")
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Public id (and the key inventory reserves under). Random, so order ids cannot be enumerated. */
    @Column(name = "order_id", nullable = false, unique = true, length = 36)
    private String orderId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrderStatus status;

    @Column(name = "total_amount", nullable = false)
    private long totalAmount;

    @Column(name = "user_coupon_id")
    private Long userCouponId;

    @Column(name = "discount_amount", nullable = false)
    private long discountAmount;

    @Column(name = "payment_amount")
    private Long paymentAmount;

    @Column(name = "payment_id", length = 64)
    private String paymentId;

    @Column(name = "failure_reason", length = 50)
    private String failureReason;

    @Column(name = "idempotency_key", length = 64)
    private String idempotencyKey;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("productId")
    private List<OrderItem> items = new ArrayList<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Order() {
    }

    public static Order place(long userId, String idempotencyKey, Long userCouponId, List<OrderItem> items) {
        Order order = new Order();
        order.orderId = UUID.randomUUID().toString();
        order.userId = userId;
        order.idempotencyKey = idempotencyKey;
        order.userCouponId = userCouponId;
        order.status = OrderStatus.PENDING;
        items.forEach(order::addItem);
        order.totalAmount = items.stream().mapToLong(OrderItem::getLineAmount).sum();
        return order;
    }

    private void addItem(OrderItem item) {
        items.add(item);
        item.attachTo(this);
    }

    public void applyDiscount(long discount) {
        requirePending();
        this.discountAmount = discount;
    }

    /** What the customer pays: total minus the coupon discount. */
    public long amountToPay() {
        return totalAmount - discountAmount;
    }

    public void complete(String paymentId) {
        requirePending();
        this.paymentAmount = amountToPay();
        this.paymentId = paymentId;
        this.status = OrderStatus.COMPLETED;
    }

    public void cancel(String reason) {
        requirePending();
        this.status = OrderStatus.CANCELLED;
        this.failureReason = reason;
    }

    private void requirePending() {
        if (status != OrderStatus.PENDING) {
            throw new IllegalStateException("Order " + orderId + " is " + status + ", not PENDING");
        }
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getOrderId() {
        return orderId;
    }

    public Long getUserId() {
        return userId;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public long getTotalAmount() {
        return totalAmount;
    }

    public Long getUserCouponId() {
        return userCouponId;
    }

    public long getDiscountAmount() {
        return discountAmount;
    }

    public Long getPaymentAmount() {
        return paymentAmount;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public List<OrderItem> getItems() {
        return items;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
