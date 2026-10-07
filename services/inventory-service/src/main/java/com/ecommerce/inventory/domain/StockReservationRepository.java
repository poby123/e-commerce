package com.ecommerce.inventory.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface StockReservationRepository extends JpaRepository<StockReservation, Long> {

    List<StockReservation> findByOrderIdOrderByProductId(String orderId);

    /**
     * RESERVED -> RELEASED as a conditional UPDATE: of two concurrent releases only one gets 1 back,
     * so only one of them returns the stock.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE stock_reservations SET status = 'RELEASED', updated_at = NOW(6)
            WHERE id = :id AND status = 'RESERVED'
            """, nativeQuery = true)
    int markReleased(@Param("id") long id);
}
