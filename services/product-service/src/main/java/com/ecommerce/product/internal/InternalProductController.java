package com.ecommerce.product.internal;

import com.ecommerce.common.error.BusinessException;
import com.ecommerce.product.domain.ProductRepository;
import com.ecommerce.product.domain.ProductStatus;
import com.ecommerce.product.error.ProductErrorCode;
import org.springframework.data.domain.Limit;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * Service-to-service API. /internal/** has no gateway route, so it is reachable only inside the network.
 * One call for all items of an order, instead of one call per item.
 */
@RestController
@RequestMapping("/internal/products")
public class InternalProductController {

    private static final int MAX_IDS = 100;

    private final ProductRepository productRepository;

    public InternalProductController(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    public record ProductSnapshot(Long id, String name, long price, ProductStatus status) {
    }

    /** Same fields as ProductChanged: search-service indexes both the same way. */
    public record ProductExport(long productId, String name, String description, String brand, String category,
                                long price, String status, long version, Instant createdAt) {
    }

    /** Every product (any status) in id order, for rebuilding the search index. */
    @GetMapping("/export")
    @Transactional(readOnly = true)
    public List<ProductExport> export(@RequestParam(defaultValue = "0") long afterId, @RequestParam(defaultValue = "500") int size) {
        return productRepository.findByIdGreaterThanOrderByIdAsc(afterId, Limit.of(Math.min(size, 1000))).stream()
                .map(p -> new ProductExport(p.getId(), p.getName(), p.getDescription(), p.getBrand(), p.getCategory().name(),
                        p.getPrice(), p.getStatus().name(), p.getVersion(), p.getCreatedAt()))
                .toList();
    }

    /** Returns the products that exist; the caller decides what a missing id means. */
    @GetMapping
    @Transactional(readOnly = true)
    public List<ProductSnapshot> findAll(@RequestParam Set<Long> ids) {
        if (ids.size() > MAX_IDS) {
            throw new BusinessException(ProductErrorCode.TOO_MANY_IDS);
        }
        return productRepository.findAllById(ids).stream()
                .map(p -> new ProductSnapshot(p.getId(), p.getName(), p.getPrice(), p.getStatus()))
                .toList();
    }
}
