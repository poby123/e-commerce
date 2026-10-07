package com.ecommerce.coupon.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "coupon_policies")
public class CouponPolicy {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 50)
    private String code;

    @Column(nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "discount_type", nullable = false, length = 10)
    private DiscountType discountType;

    @Column(name = "discount_value", nullable = false)
    private long discountValue;

    @Column(name = "max_discount_amount")
    private Long maxDiscountAmount;

    @Column(name = "min_order_amount", nullable = false)
    private long minOrderAmount;

    @Column(name = "total_quantity")
    private Integer totalQuantity;

    /** Changed only by the conditional UPDATE in CouponIssuer, never through this entity. */
    @Column(name = "issued_quantity", nullable = false, insertable = true, updatable = false)
    private int issuedQuantity;

    @Column(name = "valid_days", nullable = false)
    private int validDays;

    @Column(name = "first_come", nullable = false)
    private boolean firstCome;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected CouponPolicy() {
    }

    public CouponPolicy(String code, String name, DiscountType discountType, long discountValue, Long maxDiscountAmount,
                        long minOrderAmount, Integer totalQuantity, int validDays, boolean firstCome) {
        this.code = code;
        this.name = name;
        this.discountType = discountType;
        this.discountValue = discountValue;
        this.maxDiscountAmount = maxDiscountAmount;
        this.minOrderAmount = minOrderAmount;
        this.totalQuantity = totalQuantity;
        this.validDays = validDays;
        this.firstCome = firstCome;
    }

    /** Never more than the order itself, and RATE is capped. */
    public long discountFor(long orderAmount) {
        long discount = switch (discountType) {
            case FIXED -> discountValue;
            case RATE -> {
                long byRate = orderAmount * discountValue / 100;
                yield maxDiscountAmount != null ? Math.min(byRate, maxDiscountAmount) : byRate;
            }
        };
        return Math.min(discount, orderAmount);
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public DiscountType getDiscountType() {
        return discountType;
    }

    public long getDiscountValue() {
        return discountValue;
    }

    public Long getMaxDiscountAmount() {
        return maxDiscountAmount;
    }

    public long getMinOrderAmount() {
        return minOrderAmount;
    }

    public Integer getTotalQuantity() {
        return totalQuantity;
    }

    public int getIssuedQuantity() {
        return issuedQuantity;
    }

    public int getValidDays() {
        return validDays;
    }

    public boolean isFirstCome() {
        return firstCome;
    }
}
