package com.ecommerce.search.client;

import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;

import java.time.Instant;
import java.util.List;

/** product-service's /internal/products/export: every product, in id order, for a full rebuild. */
@HttpExchange("/internal/products")
public interface ProductExportClient {

    record ProductExport(long productId, String name, String description, String brand, String category,
                         long price, String status, long version, Instant createdAt) {
    }

    @GetExchange("/export")
    List<ProductExport> export(@RequestParam long afterId, @RequestParam int size);
}
