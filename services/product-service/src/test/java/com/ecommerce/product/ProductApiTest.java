package com.ecommerce.product;

import com.ecommerce.common.web.AuthHeaders;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.MySQLContainer;

import java.util.Map;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"spring.cloud.config.enabled=false", "eureka.client.enabled=false",
        "spring.kafka.listener.auto-startup=false", "spring.kafka.admin.auto-create=false"})
@AutoConfigureMockMvc
@Import(ProductApiTest.Containers.class)
class ProductApiTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class Containers {
        @Bean
        @ServiceConnection
        MySQLContainer<?> mysql() {
            return new MySQLContainer<>("mysql:8.4");
        }
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void adminCreatesProductAndAnyoneReadsIt() throws Exception {
        long id = create("Mug", 12_000);

        mvc.perform(get("/api/products/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Mug"))
                .andExpect(jsonPath("$.price").value(12_000))
                .andExpect(jsonPath("$.status").value("ON_SALE"));
        mvc.perform(get("/api/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].id").value(hasItem((int) id)))
                .andExpect(jsonPath("$.page.totalElements").isNumber());
    }

    @Test
    void writesRequireAdmin() throws Exception {
        Map<String, Object> body = Map.of("name", "Mug", "price", 1000);
        mvc.perform(json(post("/api/products"), body))
                .andExpect(status().isUnauthorized());
        mvc.perform(json(post("/api/products"), body).header(AuthHeaders.USER_ID, 1).header(AuthHeaders.USER_ROLES, "USER"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMMON-403"));
    }

    @Test
    void suspendedProductLeavesTheListButStaysVisibleInternally() throws Exception {
        long id = create("Old lamp", 30_000);
        mvc.perform(asAdmin(json(patch("/api/products/{id}", id), Map.of("status", "SUSPENDED"))))
                .andExpect(status().isOk());

        mvc.perform(get("/api/products").param("size", "100"))
                .andExpect(jsonPath("$.content[*].id").value(not(hasItem((int) id))));
        // Order service must see it to reject the order with a clear reason.
        mvc.perform(get("/internal/products").param("ids", String.valueOf(id)))
                .andExpect(jsonPath("$[0].status").value("SUSPENDED"));
    }

    @Test
    void internalBulkLookupReturnsOnlyExistingProducts() throws Exception {
        long a = create("A", 1_000);
        long b = create("B", 2_000);

        mvc.perform(get("/internal/products").param("ids", a + "," + b + ",999999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[?(@.id == " + b + ")].price").value(2_000));
    }

    @Autowired
    org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Test
    void everyChangePublishesTheFullStateWithAGrowingVersion() throws Exception {
        long id = create("Lamp", 30_000);
        mvc.perform(asAdmin(json(patch("/api/products/{id}", id), Map.of("price", 25_000, "brand", "Lumi", "category", "LIVING"))))
                .andExpect(status().isOk());

        var events = jdbc.queryForList("""
                SELECT JSON_EXTRACT(payload, '$.version') AS version, JSON_EXTRACT(payload, '$.price') AS price,
                       JSON_UNQUOTE(JSON_EXTRACT(payload, '$.category')) AS category
                FROM outbox WHERE aggregate_type = 'product.events' AND aggregate_id = ? ORDER BY seq""", String.valueOf(id));
        org.assertj.core.api.Assertions.assertThat(events).hasSize(2);
        org.assertj.core.api.Assertions.assertThat(String.valueOf(events.get(1).get("price"))).isEqualTo("25000");
        org.assertj.core.api.Assertions.assertThat(String.valueOf(events.get(1).get("category"))).isEqualTo("LIVING");
        org.assertj.core.api.Assertions.assertThat(Long.parseLong(String.valueOf(events.get(1).get("version"))))
                .isGreaterThan(Long.parseLong(String.valueOf(events.get(0).get("version"))));
    }

    @Test
    void exportPagesThroughAllProductsByKeyset() throws Exception {
        long a = create("Export A", 1_000);
        long b = create("Export B", 2_000);
        mvc.perform(get("/internal/products/export").param("afterId", String.valueOf(a - 1)).param("size", "1"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].productId").value(a));
        mvc.perform(get("/internal/products/export").param("afterId", String.valueOf(a)).param("size", "10"))
                .andExpect(jsonPath("$[0].productId").value(b));
    }

    @Test
    void invalidPriceIsRejected() throws Exception {
        mvc.perform(asAdmin(json(post("/api/products"), Map.of("name", "Free", "price", 0))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("price"));
    }

    @Test
    void unknownProductIs404() throws Exception {
        mvc.perform(get("/api/products/{id}", 999_999))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRODUCT-001"));
    }

    private long create(String name, long price) throws Exception {
        String body = mvc.perform(asAdmin(json(post("/api/products"), Map.of("name", name, "price", price))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asLong();
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, Object body) throws Exception {
        return request.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
    }

    private static MockHttpServletRequestBuilder asAdmin(MockHttpServletRequestBuilder request) {
        return request.header(AuthHeaders.USER_ID, 1).header(AuthHeaders.USER_ROLES, "ADMIN");
    }
}
