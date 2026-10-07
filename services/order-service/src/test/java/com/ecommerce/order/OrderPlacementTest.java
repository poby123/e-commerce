package com.ecommerce.order;

import com.ecommerce.common.web.AuthHeaders;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.MySQLContainer;

import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.serverError;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Real MySQL; product-service and inventory-service are WireMock stubs. The clients still go through the
 * load balancer: "product-service" resolves to the stub via a static instance list instead of Eureka.
 */
@SpringBootTest(properties = {
        "spring.cloud.config.enabled=false",
        "eureka.client.enabled=false",
        "ecommerce.clients.inventory.read-timeout=500ms",
})
@AutoConfigureMockMvc
@Import(OrderPlacementTest.Containers.class)
class OrderPlacementTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class Containers {
        @Bean
        @ServiceConnection
        MySQLContainer<?> mysql() {
            return new MySQLContainer<>("mysql:8.4");
        }
    }

    @RegisterExtension
    static WireMockExtension services = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    @DynamicPropertySource
    static void serviceInstances(DynamicPropertyRegistry registry) {
        registry.add("spring.cloud.discovery.client.simple.instances.product-service[0].uri", services::baseUrl);
        registry.add("spring.cloud.discovery.client.simple.instances.inventory-service[0].uri", services::baseUrl);
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper objectMapper;

    @BeforeEach
    void products() {
        services.stubFor(WireMock.get(urlPathEqualTo("/internal/products")).willReturn(okJson("""
                [{"id": 1, "name": "Mug", "price": 12000, "status": "ON_SALE"},
                 {"id": 2, "name": "Coaster", "price": 3000, "status": "ON_SALE"},
                 {"id": 3, "name": "Old lamp", "price": 30000, "status": "SUSPENDED"}]
                """)));
        services.stubFor(WireMock.post("/internal/reservations").willReturn(okJson("{\"status\": \"RESERVED\"}")));
    }

    @Test
    void confirmedOrderKeepsThePriceAtOrderTime() throws Exception {
        JsonNode order = read(place(7, null, Map.of(1L, 2, 2L, 1)).andExpect(status().isCreated()));

        assertThat(order.get("status").asText()).isEqualTo("CONFIRMED");
        assertThat(order.get("totalAmount").asLong()).isEqualTo(27_000);
        assertThat(order.get("items").get(0).get("productName").asText()).isEqualTo("Mug");
        // Inventory reserved under this order's id, so a retry would be recognised as the same order.
        services.verify(postRequestedFor(urlPathEqualTo("/internal/reservations"))
                .withRequestBody(matchingJsonPath("$.orderId", equalTo(order.get("orderId").asText()))));
    }

    @Test
    void sameProductTwiceBecomesOneLine() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("items", List.of(
                Map.of("productId", 1, "quantity", 1), Map.of("productId", 1, "quantity", 2))));
        JsonNode order = read(mvc.perform(asUser(7, post("/api/orders"))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated()));

        assertThat(order.get("items")).hasSize(1);
        assertThat(order.get("items").get(0).get("quantity").asInt()).isEqualTo(3);
    }

    @Test
    void productNotOnSaleIsRejectedBeforeTouchingStock() throws Exception {
        place(7, null, Map.of(3L, 1))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ORDER-002"));
        place(7, null, Map.of(404L, 1))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ORDER-001"));

        services.verify(0, postRequestedFor(urlPathEqualTo("/internal/reservations")));
    }

    @Test
    void outOfStockRejectsTheOrderAndKeepsItForHistory() throws Exception {
        services.stubFor(WireMock.post("/internal/reservations").willReturn(aResponse().withStatus(409)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"code\": \"INVENTORY-001\", \"message\": \"Not enough stock\"}")));

        place(7, null, Map.of(1L, 1))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER-003"));

        String orderId = latestOrderId(7);
        mvc.perform(asUser(7, get("/api/orders/{id}", orderId)))
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.failureReason").value("OUT_OF_STOCK"));
    }

    @Test
    void repeatedIdempotencyKeyReturnsTheSameOrder() throws Exception {
        JsonNode first = read(place(8, "click-1", Map.of(1L, 1)).andExpect(status().isCreated()));
        JsonNode second = read(place(8, "click-1", Map.of(1L, 1)).andExpect(status().isCreated()));

        assertThat(second.get("orderId").asText()).isEqualTo(first.get("orderId").asText());
        services.verify(1, postRequestedFor(urlPathEqualTo("/internal/reservations")));
    }

    @Test
    void ordersAreVisibleOnlyToTheirOwner() throws Exception {
        String orderId = read(place(9, null, Map.of(1L, 1))).get("orderId").asText();

        mvc.perform(asUser(9, get("/api/orders/{id}", orderId))).andExpect(status().isOk());
        mvc.perform(asUser(10, get("/api/orders/{id}", orderId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER-004"));
    }

    @Test
    void productServiceDownMeansNoOrder() throws Exception {
        services.stubFor(WireMock.get(urlPathEqualTo("/internal/products")).willReturn(serverError()));

        place(11, null, Map.of(1L, 1))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("ORDER-005"));
        mvc.perform(asUser(11, get("/api/orders"))).andExpect(jsonPath("$.page.totalElements").value(0));
    }

    /**
     * The gap step 3 closes. Inventory is too slow: it may have reserved the stock after we gave up. The order
     * stays PENDING (202), and nothing ever confirms it or gives the stock back.
     */
    @Test
    void inventoryTimeoutLeavesOrderPending() throws Exception {
        services.stubFor(WireMock.post("/internal/reservations").willReturn(okJson("{\"status\": \"RESERVED\"}").withFixedDelay(1_500)));

        JsonNode order = read(place(12, null, Map.of(1L, 1)).andExpect(status().isAccepted()));

        assertThat(order.get("status").asText()).isEqualTo("PENDING");
        mvc.perform(asUser(12, get("/api/orders/{id}", order.get("orderId").asText())))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    private ResultActions place(long userId, String idempotencyKey, Map<Long, Integer> items) throws Exception {
        List<Map<String, Object>> lines = items.entrySet().stream()
                .map(e -> Map.<String, Object>of("productId", e.getKey(), "quantity", e.getValue())).toList();
        MockHttpServletRequestBuilder request = asUser(userId, post("/api/orders"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("items", lines)));
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey);
        }
        return mvc.perform(request);
    }

    private String latestOrderId(long userId) throws Exception {
        return read(mvc.perform(asUser(userId, get("/api/orders")))).get("content").get(0).get("orderId").asText();
    }

    private JsonNode read(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private static MockHttpServletRequestBuilder asUser(long userId, MockHttpServletRequestBuilder request) {
        return request.header(AuthHeaders.USER_ID, userId).header(AuthHeaders.USER_ROLES, "USER");
    }
}
