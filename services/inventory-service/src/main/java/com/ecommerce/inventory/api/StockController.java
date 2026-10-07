package com.ecommerce.inventory.api;

import com.ecommerce.common.error.BusinessException;
import com.ecommerce.common.web.RequiresRole;
import com.ecommerce.inventory.domain.StockRepository;
import com.ecommerce.inventory.error.InventoryErrorCode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/inventory")
public class StockController {

    private final StockRepository stockRepository;

    public StockController(StockRepository stockRepository) {
        this.stockRepository = stockRepository;
    }

    public record StockResponse(Long productId, int quantity) {
    }

    public record SetStockRequest(@PositiveOrZero @Max(1_000_000) int quantity) {
    }

    @GetMapping("/{productId}")
    @Transactional(readOnly = true)
    public StockResponse get(@PathVariable Long productId) {
        return stockRepository.findById(productId)
                .map(stock -> new StockResponse(stock.getProductId(), stock.getQuantity()))
                .orElseThrow(() -> new BusinessException(InventoryErrorCode.STOCK_NOT_FOUND));
    }

    /** Sets the absolute quantity (stock-taking). Orders in flight keep what they already reserved. */
    @PutMapping("/{productId}")
    @RequiresRole("ADMIN")
    @Transactional
    public StockResponse set(@PathVariable Long productId, @Valid @RequestBody SetStockRequest request) {
        stockRepository.upsert(productId, request.quantity());
        return new StockResponse(productId, request.quantity());
    }
}
