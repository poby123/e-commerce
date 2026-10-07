package com.ecommerce.inventory.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StockRepository extends JpaRepository<Stock, Long> {

    /**
     * Check and decrement in one statement. InnoDB takes the row lock for the UPDATE, so concurrent orders are
     * serialized on the row and the condition is re-evaluated on the latest value: no oversell, no retry loop.
     *
     * @return 1 when decremented, 0 when there is not enough stock (or no stock row)
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE stocks SET quantity = quantity - :quantity, updated_at = NOW(6)
            WHERE product_id = :productId AND quantity >= :quantity
            """, nativeQuery = true)
    int decrease(@Param("productId") long productId, @Param("quantity") int quantity);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE stocks SET quantity = quantity + :quantity, updated_at = NOW(6)
            WHERE product_id = :productId
            """, nativeQuery = true)
    int increase(@Param("productId") long productId, @Param("quantity") int quantity);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            INSERT INTO stocks (product_id, quantity, updated_at) VALUES (:productId, :quantity, NOW(6))
            ON DUPLICATE KEY UPDATE quantity = :quantity, updated_at = NOW(6)
            """, nativeQuery = true)
    void upsert(@Param("productId") long productId, @Param("quantity") int quantity);
}
