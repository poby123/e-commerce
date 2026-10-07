package com.ecommerce.search.api;

import java.util.List;

public final class SearchDtos {

    private SearchDtos() {
    }

    public enum Sort { RELEVANCE, PRICE_ASC, PRICE_DESC, NEWEST }

    /** KEYWORD: BM25 only. SEMANTIC: vectors only. HYBRID: both, fused by reciprocal rank (ADR-0012). */
    public enum Mode { KEYWORD, SEMANTIC, HYBRID }

    public record SearchQuery(String q, List<String> categories, List<String> brands, Long minPrice, Long maxPrice,
                              Sort sort, int page, int size, Mode mode) {

        public SearchQuery(String q, List<String> categories, List<String> brands, Long minPrice, Long maxPrice,
                           Sort sort, int page, int size) {
            this(q, categories, brands, minPrice, maxPrice, sort, page, size, Mode.KEYWORD);
        }
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
