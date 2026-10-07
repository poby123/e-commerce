package com.ecommerce.payment.domain;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class Payments {

    public enum Status { APPROVED, DECLINED, CANCELLED }

    public record Payment(long id, String orderId, Long userId, long amount, Status status, String pgTransaction,
                          String failureReason) {
    }

    private static final RowMapper<Payment> ROW = (rs, n) -> new Payment(rs.getLong("id"), rs.getString("order_id"),
            (Long) rs.getObject("user_id", Long.class), rs.getLong("amount"), Status.valueOf(rs.getString("status")),
            rs.getString("pg_transaction"), rs.getString("failure_reason"));

    private final JdbcTemplate jdbc;

    public Payments(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Payment> find(String orderId) {
        return jdbc.query("SELECT * FROM payments WHERE order_id = ?", ROW, orderId).stream().findFirst();
    }

    /** @return false if the order already has a payment row (it wins; read it back). */
    public boolean insert(String orderId, Long userId, long amount, Status status, String pgTransaction, String failureReason) {
        try {
            jdbc.update("""
                    INSERT INTO payments (order_id, user_id, amount, status, pg_transaction, failure_reason, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, NOW(6), NOW(6))
                    """, orderId, userId, amount, status.name(), pgTransaction, failureReason);
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    public boolean cancelApproved(String orderId) {
        return jdbc.update("UPDATE payments SET status = 'CANCELLED', updated_at = NOW(6) WHERE order_id = ? AND status = 'APPROVED'",
                orderId) == 1;
    }
}
