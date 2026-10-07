package com.ecommerce.search.api;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.aggregations.Aggregation;
import co.elastic.clients.elasticsearch._types.aggregations.AggregationRange;
import co.elastic.clients.elasticsearch._types.aggregations.StringTermsBucket;
import co.elastic.clients.elasticsearch._types.query_dsl.Operator;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch._types.query_dsl.TextQueryType;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.json.JsonData;
import com.ecommerce.search.api.SearchDtos.Bucket;
import com.ecommerce.search.api.SearchDtos.Facets;
import com.ecommerce.search.api.SearchDtos.Mode;
import com.ecommerce.search.api.SearchDtos.PriceBucket;
import com.ecommerce.search.api.SearchDtos.SearchQuery;
import com.ecommerce.search.api.SearchDtos.SearchResult;
import com.ecommerce.search.index.ProductDocument;
import com.ecommerce.search.embedding.Embedder;
import com.ecommerce.search.index.ProductIndex;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Product search on the read alias.
 *
 * <p>Multi-select facets: category and brand filters go in post_filter, and each facet's aggregation applies every
 * filter except its own. Picking brand A still shows how many hits brands B and C would add, instead of a
 * brand list that collapses to A.
 */
@Component
public class ProductSearch {

    private static final List<long[]> PRICE_RANGES = List.of(
            new long[]{0, 10_000}, new long[]{10_000, 30_000}, new long[]{30_000, 100_000}, new long[]{100_000, -1});

    /**
     * @param lexicalCandidates BM25 hits taken into the fusion
     * @param vectorCandidates  nearest neighbours taken into the fusion. kNN always returns k results, however
     *                          unrelated; a small k keeps the semantic tail short (tuned with search-eval)
     * @param rrfK              the constant in 1 / (k + rank); 60 is the value from the RRF paper
     * @param minSimilarity     neighbours below this cosine are dropped (0 = keep all). Without it a query for
     *                          something the catalog does not sell still returns vectorCandidates products
     */
    @ConfigurationProperties("ecommerce.search.hybrid")
    public record HybridProperties(@DefaultValue("100") int lexicalCandidates,
                                   @DefaultValue("10") int vectorCandidates,
                                   @DefaultValue("60") int rrfK,
                                   @DefaultValue("0") float minSimilarity) {
    }

    private final ElasticsearchClient client;
    private final Embedder embedder;
    private final HybridProperties hybrid;

    public ProductSearch(ElasticsearchClient client, Embedder embedder, HybridProperties hybrid) {
        this.client = client;
        this.embedder = embedder;
        this.hybrid = hybrid;
    }

    public SearchResult search(SearchQuery query) throws IOException {
        if (query.mode() == Mode.KEYWORD || !hasText(query.q())) {
            return keyword(query);
        }
        List<Query> base = baseFilters(query);
        List<String> lexical = query.mode() == Mode.SEMANTIC ? List.of() : lexicalIds(query, base);
        List<String> semantic = vectorIds(query, base);
        return overCandidates(query, reciprocalRankFusion(List.of(lexical, semantic)));
    }

    /**
     * Reciprocal rank fusion: score = sum over lists of 1 / (k + rank). Uses ranks, not scores, so BM25 (unbounded)
     * and cosine (0..1, and squeezed into ~0.75-0.9 by e5) need no calibration against each other.
     * Done here because Elasticsearch's own RRF retriever needs a paid licence.
     */
    public List<String> reciprocalRankFusion(List<List<String>> rankings) {
        Map<String, Double> scores = new LinkedHashMap<>();
        for (List<String> ranking : rankings) {
            for (int rank = 0; rank < ranking.size(); rank++) {
                scores.merge(ranking.get(rank), 1.0 / (hybrid.rrfK() + rank + 1), Double::sum);
            }
        }
        return scores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed().thenComparing(Map.Entry::getKey))
                .map(Map.Entry::getKey)
                .toList();
    }

    private List<String> lexicalIds(SearchQuery query, List<Query> base) throws IOException {
        return client.search(s -> s.index(ProductIndex.READ)
                .query(q -> q.bool(b -> b.must(textQuery(query.q())).filter(base)))
                .size(hybrid.lexicalCandidates())
                .source(src -> src.fetch(false)), ProductDocument.class)
                .hits().hits().stream().map(Hit::id).toList();
    }

    private List<String> vectorIds(SearchQuery query, List<Query> base) throws IOException {
        List<Float> vector = toList(embedder.embedQuery(query.q()));
        return client.search(s -> s.index(ProductIndex.READ)
                .knn(k -> {
                    k.field("embedding").queryVector(vector).k(hybrid.vectorCandidates())
                            .numCandidates(Math.max(100, hybrid.vectorCandidates() * 10)).filter(base);
                    // For dot_product on unit vectors this is the cosine itself.
                    return hybrid.minSimilarity() > 0 ? k.similarity(hybrid.minSimilarity()) : k;
                })
                .size(hybrid.vectorCandidates())
                .source(src -> src.fetch(false)), ProductDocument.class)
                .hits().hits().stream().map(Hit::id).toList();
    }

    /**
     * Facets and filters over the fused candidates, with the same multi-select rules as keyword search; items keep
     * the fused order unless another sort was asked for.
     */
    private SearchResult overCandidates(SearchQuery query, List<String> fused) throws IOException {
        if (fused.isEmpty()) {
            return new SearchResult(0, query.page(), query.size(), List.of(), new Facets(List.of(), List.of(), List.of()));
        }
        Query categoryFilter = terms("category", query.categories());
        Query brandFilter = terms("brand", query.brands());
        SearchResponse<ProductDocument> response = client.search(s -> s.index(ProductIndex.READ)
                .query(q -> q.ids(i -> i.values(fused)))
                .postFilter(both(categoryFilter, brandFilter))
                .aggregations(aggregations(categoryFilter, brandFilter))
                .size(fused.size())
                .source(src -> src.filter(f -> f.excludes("embedding")))
                .highlight(h -> h.highlightQuery(textQuery(query.q())).fields("name", f -> f.preTags("<em>").postTags("</em>"))),
                ProductDocument.class);

        Map<String, Hit<ProductDocument>> byId = new LinkedHashMap<>();
        response.hits().hits().forEach(h -> byId.put(h.id(), h));
        List<Hit<ProductDocument>> ordered = new ArrayList<>(fused.stream().filter(byId::containsKey).map(byId::get).toList());
        switch (query.sort()) {
            case PRICE_ASC -> ordered.sort(Comparator.comparingLong((Hit<ProductDocument> h) -> h.source().price()));
            case PRICE_DESC -> ordered.sort(Comparator.comparingLong((Hit<ProductDocument> h) -> h.source().price()).reversed());
            case NEWEST -> ordered.sort(Comparator.comparing((Hit<ProductDocument> h) -> h.source().createdAt()).reversed());
            case RELEVANCE -> { }
        }
        int from = Math.min(query.page() * query.size(), ordered.size());
        List<SearchDtos.Hit> page = ordered.subList(from, Math.min(from + query.size(), ordered.size())).stream()
                .map(ProductSearch::toHit).toList();
        return new SearchResult(ordered.size(), query.page(), query.size(), page, facets(response));
    }

    private SearchResult keyword(SearchQuery query) throws IOException {
        List<Query> base = baseFilters(query);
        Query text = hasText(query.q()) ? textQuery(query.q()) : Query.of(q -> q.matchAll(m -> m));
        Query categoryFilter = terms("category", query.categories());
        Query brandFilter = terms("brand", query.brands());

        SearchResponse<ProductDocument> response = client.search(s -> {
            s.index(ProductIndex.READ)
                    .query(q -> q.bool(b -> b.must(text).filter(base)))
                    .postFilter(both(categoryFilter, brandFilter))
                    .aggregations(aggregations(categoryFilter, brandFilter))
                    .source(src -> src.filter(f -> f.excludes("embedding")))
                    .from(query.page() * query.size())
                    .size(query.size())
                    .trackTotalHits(t -> t.enabled(true))
                    .highlight(h -> h.fields("name", f -> f.preTags("<em>").postTags("</em>")));
            switch (query.sort()) {
                case PRICE_ASC -> s.sort(o -> o.field(f -> f.field("price").order(SortOrder.Asc)));
                case PRICE_DESC -> s.sort(o -> o.field(f -> f.field("price").order(SortOrder.Desc)));
                case NEWEST -> s.sort(o -> o.field(f -> f.field("createdAt").order(SortOrder.Desc)));
                case RELEVANCE -> s.sort(o -> o.score(sc -> sc.order(SortOrder.Desc)));
            }
            // Ties (and equal scores) in a stable order, so paging does not repeat or skip products.
            return s.sort(o -> o.field(f -> f.field("productId").order(SortOrder.Asc)));
        }, ProductDocument.class);

        List<SearchDtos.Hit> items = response.hits().hits().stream().map(ProductSearch::toHit).toList();
        return new SearchResult(response.hits().total() == null ? items.size() : response.hits().total().value(),
                query.page(), query.size(), items, facets(response));
    }

    private static List<Query> baseFilters(SearchQuery query) {
        List<Query> base = new ArrayList<>();
        base.add(Query.of(q -> q.term(t -> t.field("status").value("ON_SALE"))));
        if (query.minPrice() != null || query.maxPrice() != null) {
            base.add(Query.of(q -> q.range(r -> r.number(n -> {
                n.field("price");
                if (query.minPrice() != null) n.gte(query.minPrice().doubleValue());
                if (query.maxPrice() != null) n.lte(query.maxPrice().doubleValue());
                return n;
            }))));
        }
        return base;
    }

    private static Query textQuery(String q) {
        return Query.of(x -> x.multiMatch(m -> m.query(q)
                .fields("name^3", "brand.text^2", "description")
                .type(TextQueryType.BestFields)
                .operator(Operator.And)));
    }

    private static Map<String, Aggregation> aggregations(Query categoryFilter, Query brandFilter) {
        Map<String, Aggregation> aggs = new LinkedHashMap<>();
        aggs.put("categories", filtered(brandFilter, Aggregation.of(a -> a.terms(t -> t.field("category").size(20)))));
        aggs.put("brands", filtered(categoryFilter, Aggregation.of(a -> a.terms(t -> t.field("brand").size(20)))));
        aggs.put("prices", filtered(both(categoryFilter, brandFilter), Aggregation.of(a -> a.range(r -> r.field("price")
                .ranges(PRICE_RANGES.stream().map(ProductSearch::range).toList())))));
        return aggs;
    }

    private static List<Float> toList(float[] v) {
        List<Float> list = new ArrayList<>(v.length);
        for (float x : v) list.add(x);
        return list;
    }

    /**
     * Suggestions for a search box, from two fields:
     * <ul>
     *   <li>name.prefix: the whole name without spaces, so "무선블루" (typed across words, no space) matches
     *       "무선 블루투스 이어폰" from its start;</li>
     *   <li>name.autocomplete: each Nori token's prefixes, so "블루" matches a word in the middle.</li>
     * </ul>
     * Whole-name matches rank first (boost). Typing in progress is not re-analysed by Nori: "무선충" must not be
     * split into 무선 + 충.
     */
    public List<String> autocomplete(String prefix, int size) throws IOException {
        if (!hasText(prefix)) {
            return List.of();
        }
        SearchResponse<ProductDocument> response = client.search(s -> s.index(ProductIndex.READ)
                .query(q -> q.bool(b -> b
                        .should(m -> m.match(mt -> mt.field("name.prefix").query(prefix).boost(2.0f)))
                        .should(m -> m.match(mt -> mt.field("name.autocomplete").query(prefix).operator(Operator.And)))
                        .minimumShouldMatch("1")
                        .filter(f -> f.term(t -> t.field("status").value("ON_SALE")))))
                .collapse(c -> c.field("name.raw"))
                .source(src -> src.filter(f -> f.excludes("embedding")))
                .size(size), ProductDocument.class);
        return response.hits().hits().stream().map(h -> h.source().name()).toList();
    }

    private static SearchDtos.Hit toHit(Hit<ProductDocument> hit) {
        ProductDocument d = hit.source();
        List<String> highlight = hit.highlight().get("name");
        return new SearchDtos.Hit(d.productId(), d.name(), highlight == null || highlight.isEmpty() ? d.name() : highlight.getFirst(),
                d.brand(), d.category(), d.price());
    }

    private static Facets facets(SearchResponse<ProductDocument> response) {
        var aggs = response.aggregations();
        return new Facets(
                termBuckets(aggs.get("categories").filter().aggregations().get("inner").sterms().buckets().array()),
                termBuckets(aggs.get("brands").filter().aggregations().get("inner").sterms().buckets().array()),
                aggs.get("prices").filter().aggregations().get("inner").range().buckets().array().stream()
                        .map(b -> new PriceBucket(b.key(), b.from() == null ? null : b.from().longValue(),
                                b.to() == null ? null : b.to().longValue(), b.docCount()))
                        .toList());
    }

    private static List<Bucket> termBuckets(List<StringTermsBucket> buckets) {
        return buckets.stream().map(b -> new Bucket(b.key().stringValue(), b.docCount())).toList();
    }

    private static Aggregation filtered(Query filter, Aggregation inner) {
        return Aggregation.of(a -> a.filter(filter).aggregations("inner", inner));
    }

    private static Query terms(String field, List<String> values) {
        if (values == null || values.isEmpty()) {
            return Query.of(q -> q.matchAll(m -> m));
        }
        return Query.of(q -> q.terms(t -> t.field(field).terms(v -> v.value(values.stream().map(FieldValue::of).toList()))));
    }

    private static Query both(Query a, Query b) {
        return Query.of(q -> q.bool(x -> x.filter(a, b)));
    }

    private static AggregationRange range(long[] r) {
        return AggregationRange.of(x -> {
            x.from((double) r[0]);
            if (r[1] > 0) x.to((double) r[1]);
            return x;
        });
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}
