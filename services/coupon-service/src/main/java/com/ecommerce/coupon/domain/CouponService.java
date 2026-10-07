package com.ecommerce.coupon.domain;

import com.ecommerce.common.error.BusinessException;
import com.ecommerce.coupon.domain.UserCoupons.Status;
import com.ecommerce.coupon.domain.UserCoupons.UserCoupon;
import com.ecommerce.coupon.error.CouponErrorCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

@Service
public class CouponService {

    /** Why a reservation was refused; sent back to the saga as CouponRejected.reason. */
    public static class Rejected extends RuntimeException {
        public Rejected(String reason) {
            super(reason, null, false, false);
        }

        public String reason() {
            return getMessage();
        }
    }

    private final CouponPolicyRepository policies;
    private final UserCoupons userCoupons;
    private final JdbcTemplate jdbc;

    public CouponService(CouponPolicyRepository policies, UserCoupons userCoupons, JdbcTemplate jdbc) {
        this.policies = policies;
        this.userCoupons = userCoupons;
        this.jdbc = jdbc;
    }

    /**
     * Takes one from the policy's quantity with a conditional UPDATE, then inserts the user's coupon (the unique
     * key catches a second request and rolls the UPDATE back).
     *
     * <p>The order matters. Inserting first deadlocks under load: the INSERT's foreign-key check takes a shared
     * lock on the policy row, and two transactions holding that shared lock both wait to upgrade it for the
     * UPDATE. Updating first takes the exclusive lock up front, so the FK check never conflicts.
     * Every issue still serializes on the policy row; step 4 moves first-come issuance to Redis for that reason.
     */
    @Transactional
    public UserCoupon issue(long policyId, long userId) {
        CouponPolicy policy = policies.findById(policyId).orElseThrow(() -> new BusinessException(CouponErrorCode.POLICY_NOT_FOUND));
        if (policy.isFirstCome()) {
            throw new BusinessException(CouponErrorCode.WRONG_ISSUE_PATH);
        }
        int taken = jdbc.update("""
                UPDATE coupon_policies SET issued_quantity = issued_quantity + 1
                WHERE id = ? AND (total_quantity IS NULL OR issued_quantity < total_quantity)
                """, policyId);
        if (taken == 0) {
            throw new BusinessException(CouponErrorCode.SOLD_OUT);
        }
        if (!userCoupons.insert(policyId, userId, Instant.now().plus(Duration.ofDays(policy.getValidDays())))) {
            throw new BusinessException(CouponErrorCode.ALREADY_ISSUED);
        }
        return userCoupons.find(policyId, userId).orElseThrow();
    }

    /** @return the discount; the same order asking again gets the same answer. */
    @Transactional
    public long reserve(String orderId, long userId, long userCouponId, long orderAmount) {
        if (releasedBeforeReserve(orderId)) {
            throw new Rejected("CANCELLED");
        }
        UserCoupon coupon = userCoupons.find(userCouponId).filter(c -> c.userId() == userId)
                .orElseThrow(() -> new Rejected("COUPON_NOT_FOUND"));
        if (coupon.status() == Status.RESERVED && orderId.equals(coupon.orderId())) {
            return coupon.discountAmount();
        }
        if (coupon.status() != Status.ISSUED) {
            throw new Rejected("COUPON_NOT_AVAILABLE");
        }
        if (!coupon.expiresAt().isAfter(Instant.now())) {
            throw new Rejected("COUPON_EXPIRED");
        }
        CouponPolicy policy = policies.findById(coupon.policyId()).orElseThrow();
        if (orderAmount < policy.getMinOrderAmount()) {
            throw new Rejected("MIN_ORDER_AMOUNT_NOT_MET");
        }
        long discount = policy.discountFor(orderAmount);
        if (!userCoupons.reserve(userCouponId, userId, orderId, discount)) {
            // Another order reserved it between our read and this UPDATE.
            throw new Rejected("COUPON_NOT_AVAILABLE");
        }
        return discount;
    }

    @Transactional
    public void confirm(String orderId, long userCouponId) {
        userCoupons.confirm(userCouponId, orderId);
    }

    /** Back to ISSUED. If this order never reserved it (the reserve is still in flight), block that reserve. */
    @Transactional
    public void release(String orderId, long userCouponId) {
        if (!userCoupons.release(userCouponId, orderId)) {
            boolean heldByThisOrder = userCoupons.find(userCouponId).map(c -> orderId.equals(c.orderId())).orElse(false);
            if (!heldByThisOrder) {
                jdbc.update("INSERT IGNORE INTO released_orders (order_id, created_at) VALUES (?, NOW(6))", orderId);
            }
        }
    }

    private boolean releasedBeforeReserve(String orderId) {
        return !jdbc.queryForList("SELECT 1 FROM released_orders WHERE order_id = ?", orderId).isEmpty();
    }
}
