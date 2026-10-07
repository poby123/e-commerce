package com.ecommerce.product.api;

import com.ecommerce.product.domain.Product;
import com.ecommerce.product.domain.ProductStatus;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public final class ProductDtos {

    private ProductDtos() {
    }

    public record CreateProductRequest(
            @NotBlank @Size(max = 100) String name,
            @Size(max = 2000) String description,
            @NotNull @Positive @Max(100_000_000) Long price
    ) {
    }

    /** Partial update: null fields are left unchanged. */
    public record UpdateProductRequest(
            @Size(min = 1, max = 100) String name,
            @Size(max = 2000) String description,
            @Positive @Max(100_000_000) Long price,
            ProductStatus status
    ) {
    }

    public record ProductResponse(Long id, String name, String description, long price, ProductStatus status,
                                  Instant createdAt) {

        public static ProductResponse from(Product product) {
            return new ProductResponse(product.getId(), product.getName(), product.getDescription(),
                    product.getPrice(), product.getStatus(), product.getCreatedAt());
        }
    }
}
