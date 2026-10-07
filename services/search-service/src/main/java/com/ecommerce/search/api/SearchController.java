package com.ecommerce.search.api;

import com.ecommerce.search.api.SearchDtos.SearchQuery;
import com.ecommerce.search.api.SearchDtos.SearchResult;
import com.ecommerce.search.api.SearchDtos.Sort;
import com.ecommerce.search.index.Reindexer;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.List;

@RestController
public class SearchController {

    private final ProductSearch productSearch;
    private final Reindexer reindexer;

    public SearchController(ProductSearch productSearch, Reindexer reindexer) {
        this.productSearch = productSearch;
        this.reindexer = reindexer;
    }

    /** Public (GET /api/search/** at the gateway). Deep paging is capped: page * size must stay under 10,000. */
    @GetMapping("/api/search/products")
    public SearchResult search(@RequestParam(required = false) @Size(max = 100) String q,
                               @RequestParam(required = false) List<String> category,
                               @RequestParam(required = false) List<String> brand,
                               @RequestParam(required = false) Long minPrice,
                               @RequestParam(required = false) Long maxPrice,
                               @RequestParam(defaultValue = "RELEVANCE") Sort sort,
                               @RequestParam(defaultValue = "0") @Min(0) @Max(499) int page,
                               @RequestParam(defaultValue = "20") @Min(1) @Max(20) int size) throws IOException {
        return productSearch.search(new SearchQuery(q, category, brand, minPrice, maxPrice, sort, page, size));
    }

    @GetMapping("/api/search/autocomplete")
    public List<String> autocomplete(@RequestParam @Size(max = 50) String q,
                                     @RequestParam(defaultValue = "8") @Min(1) @Max(20) int size) throws IOException {
        return productSearch.autocomplete(q, size);
    }

    /** Operators only: /internal/** has no gateway route. */
    @PostMapping("/internal/search/reindex")
    public Reindexer.Result reindex() throws IOException, InterruptedException {
        return reindexer.reindex();
    }
}
