package com.ecommerce.order.client;

import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;

import java.util.Collection;
import java.util.List;

@HttpExchange("/internal/products")
public interface ProductClient {

    record ProductSnapshot(Long id, String name, long price, String status) {
    }

    @GetExchange
    List<ProductSnapshot> findAll(@RequestParam Collection<Long> ids);
}
