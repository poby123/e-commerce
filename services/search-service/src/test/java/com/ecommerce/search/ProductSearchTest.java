package com.ecommerce.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.ecommerce.messaging.IncomingMessage;
import com.ecommerce.messaging.contract.ProductMessages.ProductChanged;
import com.ecommerce.search.api.ProductSearch;
import com.ecommerce.search.api.SearchDtos.Hit;
import com.ecommerce.search.api.SearchDtos.SearchQuery;
import com.ecommerce.search.api.SearchDtos.SearchResult;
import com.ecommerce.search.api.SearchDtos.Sort;
import com.ecommerce.search.index.ProductEventListener;
import com.ecommerce.search.index.ProductIndex;
import com.ecommerce.search.index.Reindexer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.cloud.config.enabled=false",
        "eureka.client.enabled=false",
        "spring.kafka.listener.auto-startup=false",
        "spring.kafka.admin.auto-create=false",
})
@Import(ProductSearchTest.Infra.class)
class ProductSearchTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class Infra {
        /** Built from the same Dockerfile as compose (8.18 + Nori), through the Docker API. */
        @Bean
        @ServiceConnection
        ElasticsearchContainer elasticsearch() throws Exception {
            String image = new ImageFromDockerfile("ecommerce-elasticsearch-test:8.18.8", false)
                    .withDockerfile(Path.of("../../docker/elasticsearch/Dockerfile").toAbsolutePath().normalize())
                    .get();
            return new ElasticsearchContainer(DockerImageName.parse(image)
                    .asCompatibleSubstituteFor("docker.elastic.co/elasticsearch/elasticsearch"))
                    .withEnv("xpack.security.enabled", "false")
                    .withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m");
        }
    }

    /** Stands in for product-service's export API during reindex. */
    static final WireMockServer productService = new WireMockServer(wireMockConfig().dynamicPort());

    static {
        productService.start();
    }

    @DynamicPropertySource
    static void productServiceInstance(DynamicPropertyRegistry registry) {
        registry.add("spring.cloud.discovery.client.simple.instances.product-service[0].uri", productService::baseUrl);
    }

    private static final AtomicLong IDS = new AtomicLong(1);

    @Autowired
    ProductEventListener listener;
    @Autowired
    ProductSearch search;
    @Autowired
    Reindexer reindexer;
    @Autowired
    ElasticsearchClient client;
    @Autowired
    ObjectMapper objectMapper;

    String tag;

    @BeforeEach
    void setUp() {
        productService.resetAll();
        // Each test uses its own brand, so tests sharing one index do not see each other's products.
        tag = "B" + UUID.randomUUID().toString().substring(0, 6);
    }

    @Test
    void koreanCompoundWordsAreSplitAndDictionaryWordsKept() throws Exception {
        long earbuds = product("무선 블루투스 이어폰 프로", "DIGITAL", 89_000);
        long humidifier = product("초음파 가습기 대용량", "LIVING", 45_000);
        long dryer = product("빨래 습기 제거제", "LIVING", 9_000);
        refresh();

        // No space in the query: Nori splits it into 블루투스 + 이어폰.
        assertThat(ids(query("블루투스이어폰"))).containsExactly(earbuds);
        // "가습기" is in the user dictionary; without it Nori splits it into 가 + 습기 and also finds the dryer.
        assertThat(ids(query("가습기"))).containsExactly(humidifier);
        assertThat(ids(query("습기"))).containsExactly(dryer);
    }

    @Test
    void synonymsMatchAtSearchTime() throws Exception {
        long shoes = product("경량 러닝화", "SPORTS", 79_000);
        refresh();

        assertThat(ids(query("운동화"))).containsExactly(shoes);
        assertThat(ids(query("스니커즈"))).containsExactly(shoes);
    }

    @Test
    void facetsIgnoreTheirOwnFilterSoOtherChoicesStayVisible() throws Exception {
        String other = tag + "X";
        product("키보드 A", "DIGITAL", 20_000);
        product("키보드 B", "DIGITAL", 50_000);
        productOfBrand(other, "키보드 C", "DIGITAL", 150_000);
        refresh();

        SearchResult result = search.search(new SearchQuery("키보드", null, List.of(tag), null, null, Sort.PRICE_ASC, 0, 20));

        assertThat(result.total()).isEqualTo(2);
        assertThat(result.items()).extracting(Hit::price).containsExactly(20_000L, 50_000L);
        // The brand facet still counts the unselected brand...
        assertThat(result.facets().brands()).extracting("value", "count")
                .contains(org.assertj.core.groups.Tuple.tuple(tag, 2L), org.assertj.core.groups.Tuple.tuple(other, 1L));
        // ...while the price facet respects the brand filter.
        assertThat(result.facets().prices()).filteredOn(b -> b.count() > 0).extracting("key")
                .containsExactlyInAnyOrder("10000.0-30000.0", "30000.0-100000.0");
    }

    @Test
    void anOlderOrRepeatedEventNeverOverwritesANewerOne() throws Exception {
        long id = IDS.incrementAndGet();
        send(new ProductChanged(id, "모니터 " + tag, "", tag, "DIGITAL", 300_000, "ON_SALE", 3, Instant.now()));
        send(new ProductChanged(id, "모니터 " + tag, "", tag, "DIGITAL", 200_000, "ON_SALE", 2, Instant.now())); // late
        send(new ProductChanged(id, "모니터 " + tag, "", tag, "DIGITAL", 300_000, "ON_SALE", 3, Instant.now())); // duplicate
        refresh();

        assertThat(query("모니터").items()).extracting(Hit::price).containsExactly(300_000L);
    }

    @Test
    void suspendedProductsAreNotFound() throws Exception {
        long id = IDS.incrementAndGet();
        send(new ProductChanged(id, "단종 스피커", "", tag, "DIGITAL", 50_000, "ON_SALE", 0, Instant.now()));
        send(new ProductChanged(id, "단종 스피커", "", tag, "DIGITAL", 50_000, "SUSPENDED", 1, Instant.now()));
        refresh();

        assertThat(query("스피커").items()).isEmpty();
    }

    @Test
    void autocompleteSuggestsFromAPrefix() throws Exception {
        String charger = "무선충전기 고속 15W " + tag;
        String earbuds = "무선 블루투스 이어폰 " + tag;
        product(charger, "DIGITAL", 25_000);
        product(earbuds, "DIGITAL", 89_000);
        refresh();

        assertThat(search.autocomplete("무선충", 10)).contains(charger);          // inside a dictionary word
        assertThat(search.autocomplete("무선블루", 10)).contains(earbuds);       // across words, no space typed
        assertThat(search.autocomplete("블루", 10)).contains(earbuds);           // a word in the middle
        assertThat(search.autocomplete("무선충전기고속15", 10)).contains(charger); // letters and digits run together
    }

    @Test
    void reindexSwapsIndicesWithoutLosingUpdatesMadeDuringTheBackfill() throws Exception {
        long id = IDS.incrementAndGet();
        send(new ProductChanged(id, "의자 " + tag, "", tag, "LIVING", 100_000, "ON_SALE", 0, Instant.now()));
        refresh();
        String before = readIndex();

        // product-service's export is slow and returns version 0 (it was read before the update below).
        productService.stubFor(WireMock.get(urlPathEqualTo("/internal/products/export")).withQueryParam("afterId", WireMock.equalTo("0"))
                .willReturn(okJson(objectMapper.writeValueAsString(List.of(new ExportRow(id, "의자 " + tag, "", tag, "LIVING", 100_000, "ON_SALE", 0, Instant.now()))))
                        .withFixedDelay(3_000)));
        productService.stubFor(WireMock.get(urlPathEqualTo("/internal/products/export")).withQueryParam("afterId", WireMock.equalTo(String.valueOf(id)))
                .willReturn(okJson("[]")));

        CompletableFuture<Reindexer.Result> running = CompletableFuture.supplyAsync(() -> {
            try {
                return reindexer.reindex();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        Thread.sleep(2_500); // the new index exists and is a write target; the backfill is still waiting
        send(new ProductChanged(id, "의자 " + tag, "", tag, "LIVING", 80_000, "ON_SALE", 1, Instant.now())); // price cut
        Reindexer.Result result = running.get();
        refresh();

        assertThat(result.index()).isNotEqualTo(before);
        assertThat(readIndex()).isEqualTo(result.index());
        // The live update (v1) survived the backfill of the stale export (v0).
        assertThat(query("의자").items()).extracting(Hit::price).containsExactly(80_000L);
        assertThat(client.indices().exists(e -> e.index(before)).value()).isFalse();
    }

    record ExportRow(long productId, String name, String description, String brand, String category, long price,
                     String status, long version, Instant createdAt) {
    }

    private long product(String name, String category, long price) throws Exception {
        return productOfBrand(tag, name, category, price);
    }

    private long productOfBrand(String brand, String name, String category, long price) throws Exception {
        long id = IDS.incrementAndGet();
        send(new ProductChanged(id, name, "", brand, category, price, "ON_SALE", 0, Instant.now()));
        return id;
    }

    private void send(ProductChanged event) throws Exception {
        listener.handle(new IncomingMessage(UUID.randomUUID().toString(), "ProductChanged", String.valueOf(event.productId()),
                objectMapper.writeValueAsString(event)));
    }

    private SearchResult query(String q) throws Exception {
        return search.search(new SearchQuery(q, null, List.of(tag), null, null, Sort.RELEVANCE, 0, 20));
    }

    private static List<Long> ids(SearchResult result) {
        return result.items().stream().map(Hit::productId).toList();
    }

    private void refresh() throws Exception {
        client.indices().refresh(r -> r.index(ProductIndex.READ));
    }

    private String readIndex() throws Exception {
        return client.indices().getAlias(g -> g.name(ProductIndex.READ)).result().keySet().iterator().next();
    }
}
