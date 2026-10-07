package com.ecommerce.search.index;

import co.elastic.clients.elasticsearch.indices.get_alias.IndexAliases;
import com.ecommerce.search.client.ProductExportClient;
import com.ecommerce.search.client.ProductExportClient.ProductExport;
import com.ecommerce.search.embedding.Embedder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Rebuilds the index without downtime, e.g. after a mapping or dictionary change:
 * <ol>
 *   <li>create a new index and alias it {@value ProductIndex#BUILDING}: from now on live events go to both;</li>
 *   <li>wait until every writer has seen that alias;</li>
 *   <li>backfill every product from product-service (external versions: a live update that arrived meanwhile
 *       is newer and wins);</li>
 *   <li>swap {@value ProductIndex#READ} to the new index in one atomic alias update, then drop the old one.</li>
 * </ol>
 * Searches keep hitting the old index until the swap.
 */
@Component
public class Reindexer {

    public record Result(String index, int documents, long tookMillis) {
    }

    private static final Logger log = LoggerFactory.getLogger(Reindexer.class);
    private static final int PAGE = 500;

    private final ProductIndex productIndex;
    private final ProductExportClient products;
    private final Embedder embedder;
    private final ReentrantLock running = new ReentrantLock();

    public Reindexer(ProductIndex productIndex, ProductExportClient products, Embedder embedder) {
        this.productIndex = productIndex;
        this.products = products;
        this.embedder = embedder;
    }

    public Result reindex() throws IOException, InterruptedException {
        if (!running.tryLock()) {
            throw new IllegalStateException("A reindex is already running");
        }
        long started = System.currentTimeMillis();
        try {
            String index = productIndex.createIndex();
            var client = productIndex.client();
            client.indices().updateAliases(u -> u.actions(a -> a.add(add -> add.index(index).alias(ProductIndex.BUILDING))));
            Thread.sleep(ProductIndex.TARGET_CACHE_MILLIS * 2);

            int documents = 0;
            long afterId = 0;
            while (true) {
                List<ProductExport> page = products.export(afterId, PAGE);
                if (page.isEmpty()) {
                    break;
                }
                documents += productIndex.writeAll(index, page.stream().map(p -> new ProductIndex.VersionedDocument(
                        ProductDocument.of(p.productId(), p.name(), p.description(), p.brand(), p.category(), p.price(),
                                p.status(), p.createdAt(), embedder), p.version())).toList());
                afterId = page.getLast().productId();
            }
            client.indices().refresh(r -> r.index(index));

            List<String> old = productIndex.aliases().entrySet().stream()
                    .filter(e -> !e.getKey().equals(index))
                    .map(Map.Entry::getKey)
                    .toList();
            client.indices().updateAliases(u -> {
                old.forEach(o -> u.actions(a -> a.remove(r -> r.index(o).alias(ProductIndex.READ))));
                u.actions(a -> a.add(add -> add.index(index).alias(ProductIndex.READ)));
                u.actions(a -> a.remove(r -> r.index(index).alias(ProductIndex.BUILDING)));
                return u;
            });
            if (!old.isEmpty()) {
                client.indices().delete(d -> d.index(old));
            }
            Result result = new Result(index, documents, System.currentTimeMillis() - started);
            log.info("Reindexed into {}: {} documents in {} ms (dropped {})", index, documents, result.tookMillis(), old);
            return result;
        } finally {
            running.unlock();
        }
    }

    Map<String, IndexAliases> aliases() throws IOException {
        return productIndex.aliases();
    }
}
