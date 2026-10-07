package com.ecommerce.search.api;

import java.util.List;

public final class SearchDtos {

    private SearchDtos() {
    }

    public enum Sort { RELEVANCE, PRICE_ASC, PRICE_DESC, NEWEST }

    public record SearchQuery(String q, List<String> categories, List<String> brands, Long minPrice, Long maxPrice,
                              Sort sort, int page, int size) {
    }

    public record Hit(long productId, String name, String highlightedName, String brand, String category, long price) {
    }

    public record Bucket(String value, long count) {
    }

    public record PriceBucket(String key, Long from, Long to, long count) {
    }

    public record Facets(List<Bucket> categories, List<Bucket> brands, List<PriceBucket> prices) {
    }

    public record SearchResult(long total, int page, int size, List<Hit> items, Facets facets) {
    }
}
