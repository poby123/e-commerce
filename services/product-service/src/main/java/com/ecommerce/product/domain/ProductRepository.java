package com.ecommerce.product.domain;

import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProductRepository extends JpaRepository<Product, Long> {

    Page<Product> findByStatus(ProductStatus status, Pageable pageable);

    /** Keyset page for a full export: stable and index-only, unlike OFFSET on a large table. */
    List<Product> findByIdGreaterThanOrderByIdAsc(long afterId, Limit limit);
}
