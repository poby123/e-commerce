package com.ecommerce.order.saga;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface OrderSagaRepository extends JpaRepository<OrderSaga, String> {

    long countByState(OrderSaga.State state);

    /**
     * Overdue sagas, locked. SKIP LOCKED lets several order-service instances run the timeout job at once:
     * each takes rows the others have not locked, instead of queuing behind them or doing the work twice.
     */
    @Query(value = """
            SELECT saga_id FROM order_sagas
            WHERE state = 'RUNNING' AND deadline_at < :now
            ORDER BY deadline_at
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<String> lockOverdue(@Param("now") Instant now, @Param("limit") int limit);
}
