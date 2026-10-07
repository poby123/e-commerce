package com.ecommerce.messaging.contract;

/** order-service -> coupon-service, and the replies. The saga id is the order id. */
public final class CouponMessages {

    private CouponMessages() {
    }

    public record ReserveCoupon(String sagaId, long userId, long userCouponId, long orderAmount) {
    }

    public record ConfirmCoupon(String sagaId, long userCouponId) {
    }

    public record ReleaseCoupon(String sagaId, long userCouponId) {
    }

    public record CouponReserved(String sagaId, long discountAmount) {
    }

    public record CouponRejected(String sagaId, String reason) {
    }

    public record CouponClaimed(long policyId, long userId) {
    }
}
