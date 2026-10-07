package com.ecommerce.coupon.domain;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * user_coupons with explicit SQL. Every state change is a conditional UPDATE on the expected current state,
 * so two orders racing for the same coupon cannot both reserve it.
 */
@Repository
public class UserCoupons {

    public enum Status { ISSUED, RESERVED, USED }

    public record UserCoupon(long id, long policyId, long userId, Status status, String orderId, Long discountAmount,
                             Instant expiresAt, Instant issuedAt) {
    }

    private static final RowMapper<UserCoupon> ROW = (rs, n) -> new UserCoupon(
            rs.getLong("id"), rs.getLong("policy_id"), rs.getLong("user_id"), Status.valueOf(rs.getString("status")),
            rs.getString("order_id"), (Long) rs.getObject("discount_amount", Long.class),
            rs.getTimestamp("expires_at").toInstant(), rs.getTimestamp("issued_at").toInstant());

    private final JdbcTemplate jdbc;

    public UserCoupons(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** @return false when the user already has a coupon of this policy (unique key) */
    public boolean insert(long policyId, long userId, Instant expiresAt) {
        try {
            jdbc.update("INSERT INTO user_coupons (policy_id, user_id, status, expires_at, issued_at) VALUES (?, ?, 'ISSUED', ?, NOW(6))",
                    policyId, userId, Timestamp.from(expiresAt));
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    public Optional<UserCoupon> find(long id) {
        return jdbc.query("SELECT * FROM user_coupons WHERE id = ?", ROW, id).stream().findFirst();
    }

    public Optional<UserCoupon> find(long policyId, long userId) {
        return jdbc.query("SELECT * FROM user_coupons WHERE policy_id = ? AND user_id = ?", ROW, policyId, userId).stream().findFirst();
    }

    public List<UserCoupon> findByUser(long userId) {
        return jdbc.query("SELECT * FROM user_coupons WHERE user_id = ? ORDER BY id DESC", ROW, userId);
    }

    public boolean reserve(long id, long userId, String orderId, long discountAmount) {
        return jdbc.update("""
                UPDATE user_coupons SET status = 'RESERVED', order_id = ?, discount_amount = ?
                WHERE id = ? AND user_id = ? AND status = 'ISSUED' AND expires_at > NOW(6)
                """, orderId, discountAmount, id, userId) == 1;
    }

    public boolean confirm(long id, String orderId) {
        return jdbc.update("""
                UPDATE user_coupons SET status = 'USED', used_at = NOW(6)
                WHERE id = ? AND order_id = ? AND status = 'RESERVED'
                """, id, orderId) == 1;
    }

    public boolean release(long id, String orderId) {
        return jdbc.update("""
                UPDATE user_coupons SET status = 'ISSUED', order_id = NULL, discount_amount = NULL
                WHERE id = ? AND order_id = ? AND status = 'RESERVED'
                """, id, orderId) == 1;
    }
}
