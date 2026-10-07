package com.ecommerce.search.index;

import com.ecommerce.messaging.contract.ProductMessages.ProductChanged;
import com.ecommerce.search.embedding.Embedder;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.Map;

/**
 * One product in the search index. The product's version is not stored here: it is the document's _version.
 * embedding is written but never read back (searches exclude it from _source).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProductDocument(long productId, String name, String description, String brand, String category,
                              long price, String status, Instant createdAt, float[] embedding) {

    private static final Map<String, String> CATEGORY_WORDS = Map.of(
            "DIGITAL", "디지털 전자기기", "FASHION", "패션 의류", "BEAUTY", "뷰티 화장품", "LIVING", "생활 가전 리빙",
            "FOOD", "식품 먹거리", "SPORTS", "스포츠 운동", "ETC", "");

    public static ProductDocument from(ProductChanged e, Embedder embedder) {
        return of(e.productId(), e.name(), e.description(), e.brand(), e.category(), e.price(), e.status(), e.createdAt(), embedder);
    }

    public static ProductDocument of(long productId, String name, String description, String brand, String category,
                                     long price, String status, Instant createdAt, Embedder embedder) {
        // What the product is, in words: name, brand, category in Korean (the code "LIVING" means nothing to the
        // model), description.
        String passage = String.join(" ", name, nullToEmpty(brand), CATEGORY_WORDS.getOrDefault(category, ""), nullToEmpty(description)).strip();
        return new ProductDocument(productId, name, description, brand, category, price, status, createdAt, embedder.embedPassage(passage));
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
