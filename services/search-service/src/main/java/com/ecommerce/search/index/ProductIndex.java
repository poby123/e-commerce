package com.ecommerce.search.index;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.VersionType;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.indices.get_alias.IndexAliases;
import co.elastic.clients.transport.TransportException;
import org.elasticsearch.client.ResponseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * The physical indices behind two aliases:
 * <ul>
 *   <li>{@value #READ}: what search queries; points to exactly one index.</li>
 *   <li>{@value #BUILDING}: an index being rebuilt, if any. Writes go to both, so changes made while the new index
 *       is backfilled are not lost.</li>
 * </ul>
 * Every write uses the product's version as Elasticsearch's external version: a write with a version not higher
 * than the stored one is refused, which makes duplicates and out-of-order events harmless.
 */
@Component
public class ProductIndex {

    public static final String READ = "products";
    public static final String BUILDING = "products-building";
    private static final String PATTERN = "products-*";
    /** How long a resolved set of write targets is reused; Reindexer waits longer than this after adding BUILDING. */
    static final long TARGET_CACHE_MILLIS = 1_000;

    private static final Logger log = LoggerFactory.getLogger(ProductIndex.class);

    private final ElasticsearchClient client;
    private volatile List<String> targets = List.of();
    private volatile long targetsResolvedAt;

    public ProductIndex(ElasticsearchClient client) {
        this.client = client;
    }

    /** Creates the first index and points the read alias at it, if there is none yet. */
    public void ensureExists() throws IOException {
        if (!aliases().values().stream().anyMatch(a -> a.aliases().containsKey(READ))) {
            String index = createIndex();
            client.indices().updateAliases(u -> u.actions(a -> a.add(add -> add.index(index).alias(READ))));
            log.info("Created {} as {}", index, READ);
        }
    }

    public String createIndex() throws IOException {
        String index = "products-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS"));
        try (InputStream settings = new ClassPathResource("elasticsearch/products-index.json").getInputStream()) {
            client.indices().create(c -> c.index(index).withJson(settings));
        }
        return index;
    }

    /** @return false if Elasticsearch already had this version or a newer one (stale or duplicate event). */
    public boolean write(ProductDocument document, long productVersion) throws IOException {
        boolean applied = false;
        for (String index : writeTargets()) {
            try {
                client.index(i -> i.index(index).id(String.valueOf(document.productId())).document(document)
                        .versionType(VersionType.External).version(esVersion(productVersion)));
                applied = true;
            } catch (ElasticsearchException | TransportException | ResponseException e) {
                if (!isVersionConflict(e)) {
                    throw e;
                }
            }
        }
        return applied;
    }

    /** Bulk write to one index; version conflicts (a newer live update got there first) are expected and skipped. */
    public int writeAll(String index, List<VersionedDocument> documents) throws IOException {
        if (documents.isEmpty()) {
            return 0;
        }
        BulkRequest.Builder bulk = new BulkRequest.Builder();
        for (VersionedDocument d : documents) {
            bulk.operations(op -> op.index(i -> i.index(index).id(String.valueOf(d.document().productId()))
                    .document(d.document()).versionType(VersionType.External).version(esVersion(d.version()))));
        }
        BulkResponse response = client.bulk(bulk.build());
        int written = 0;
        for (var item : response.items()) {
            if (item.error() == null) {
                written++;
            } else if (!"version_conflict_engine_exception".equals(item.error().type())) {
                throw new IllegalStateException("Bulk index failed: " + item.error().reason());
            }
        }
        return written;
    }

    public record VersionedDocument(ProductDocument document, long version) {
    }

    /**
     * A 409 reaches us in different shapes depending on the path through the client: ElasticsearchException,
     * a TransportException, or the low-level ResponseException (an IOException).
     */
    private static boolean isVersionConflict(Exception e) {
        if (e instanceof ElasticsearchException ee) {
            return ee.status() == 409;
        }
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof ResponseException re) {
                return re.getResponse().getStatusLine().getStatusCode() == 409;
            }
        }
        return false;
    }

    List<String> writeTargets() throws IOException {
        if (System.currentTimeMillis() - targetsResolvedAt > TARGET_CACHE_MILLIS) {
            targets = aliases().entrySet().stream()
                    .filter(e -> e.getValue().aliases().containsKey(READ) || e.getValue().aliases().containsKey(BUILDING))
                    .map(Map.Entry::getKey)
                    .toList();
            targetsResolvedAt = System.currentTimeMillis();
        }
        if (targets.isEmpty()) {
            throw new IllegalStateException("No index behind " + READ + " / " + BUILDING);
        }
        return targets;
    }

    Map<String, IndexAliases> aliases() throws IOException {
        return client.indices().getAlias(g -> g.index(PATTERN)).result();
    }

    ElasticsearchClient client() {
        return client;
    }

    /** Product versions start at 0; Elasticsearch external versions are compared, so shifting by one is safe. */
    private static long esVersion(long productVersion) {
        return productVersion + 1;
    }

    static UncheckedIOException unchecked(IOException e) {
        return new UncheckedIOException(e);
    }
}
