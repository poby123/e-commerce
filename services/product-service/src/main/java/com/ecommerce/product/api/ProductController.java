package com.ecommerce.product.api;

import com.ecommerce.common.error.BusinessException;
import com.ecommerce.common.web.RequiresRole;
import com.ecommerce.product.api.ProductDtos.CreateProductRequest;
import com.ecommerce.product.api.ProductDtos.ProductResponse;
import com.ecommerce.product.api.ProductDtos.UpdateProductRequest;
import com.ecommerce.product.domain.Product;
import com.ecommerce.product.domain.ProductEvents;
import com.ecommerce.product.domain.ProductRepository;
import com.ecommerce.product.domain.ProductStatus;
import com.ecommerce.product.error.ProductErrorCode;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** GETs are public at the gateway; writes need an ADMIN. */
@RestController
@RequestMapping("/api/products")
public class ProductController {

    private final ProductRepository productRepository;
    private final ProductEvents events;

    public ProductController(ProductRepository productRepository, ProductEvents events) {
        this.productRepository = productRepository;
        this.events = events;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public Page<ProductResponse> list(@PageableDefault(size = 20, sort = "id", direction = Sort.Direction.DESC) Pageable pageable) {
        return productRepository.findByStatus(ProductStatus.ON_SALE, pageable).map(ProductResponse::from);
    }

    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    public ProductResponse get(@PathVariable Long id) {
        return ProductResponse.from(find(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresRole("ADMIN")
    @Transactional
    public ProductResponse create(@Valid @RequestBody CreateProductRequest request) {
        Product product = productRepository.saveAndFlush(new Product(request.name().strip(), request.description(),
                request.brand(), request.category(), request.price()));
        events.changed(product);
        return ProductResponse.from(product);
    }

    @PatchMapping("/{id}")
    @RequiresRole("ADMIN")
    @Transactional
    public ProductResponse update(@PathVariable Long id, @Valid @RequestBody UpdateProductRequest request) {
        Product product = find(id);
        product.update(request.name(), request.description(), request.brand(), request.category(), request.price(), request.status());
        productRepository.flush(); // bumps the version the event carries
        events.changed(product);
        return ProductResponse.from(product);
    }

    private Product find(Long id) {
        return productRepository.findById(id).orElseThrow(() -> new BusinessException(ProductErrorCode.PRODUCT_NOT_FOUND));
    }
}
