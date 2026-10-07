package com.ecommerce.order;

import com.ecommerce.common.web.AuthHeaders;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.client.WireMock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.serverError;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The HTTP side: accepting, validating and reading orders. The saga itself is in OrderSagaTest. */
class OrderPlacementTest extends OrderTestSupport {

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper objectMapper;

    @Test
    void acceptedOrderIsPendingWithItsSagaStartedInTheSameTransaction() throws Exception {
        JsonNode order = read(place(7, null, null, Map.of(1L, 2, 2L, 1)).andExpect(status().isAccepted()));

        assertThat(order.get("status").asText()).isEqualTo("PENDING");
        assertThat(order.get("totalAmount").asLong()).isEqualTo(27_000);
        assertThat(sent(order.get("orderId").asText())).containsExactly("inventory.command:ReserveStock");
    }

    @Test
    void withACouponTheSagaStartsAtTheCoupon() throws Exception {
        JsonNode order = read(place(7, null, 55L, Map.of(1L, 1)).andExpect(status().isAccepted()));

        String orderId = order.get("orderId").asText();
        assertThat(sent(orderId)).containsExactly("coupon.command:ReserveCoupon");
        assertThat(sentField(orderId, "ReserveCoupon", "orderAmount")).isEqualTo("12000");
    }

    @Test
    void sameProductTwiceBecomesOneLine() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("paymentToken", "tok_approve", "items", List.of(
                Map.of("productId", 1, "quantity", 1), Map.of("productId", 1, "quantity", 2))));
        JsonNode order = read(mvc.perform(asUser(7, post("/api/orders")).contentType(MediaType.APPLICATION_JSON).content(body)));

        assertThat(order.get("items")).hasSize(1);
        assertThat(order.get("items").get(0).get("quantity").asInt()).isEqualTo(3);
    }

    @Test
    void productNotOnSaleOrUnknownIsRejectedBeforeAnySaga() throws Exception {
        place(7, null, null, Map.of(3L, 1)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("ORDER-002"));
        place(7, null, null, Map.of(404L, 1)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("ORDER-001"));
    }

    @Test
    void paymentTokenIsRequiredAndNeverACardNumber() throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of("items", List.of(Map.of("productId", 1, "quantity", 1))));
        body.put("paymentToken", "4111111111111111");
        mvc.perform(asUser(7, post("/api/orders")).contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("paymentToken"));
    }

    @Test
    void repeatedIdempotencyKeyReturnsTheSameOrderAndStartsOneSaga() throws Exception {
        JsonNode first = read(place(8, "click-1", null, Map.of(1L, 1)));
        JsonNode second = read(place(8, "click-1", null, Map.of(1L, 1)));

        assertThat(second.get("orderId").asText()).isEqualTo(first.get("orderId").asText());
        assertThat(sent(first.get("orderId").asText())).hasSize(1);
    }

    @Test
    void ordersAreVisibleOnlyToTheirOwner() throws Exception {
        String orderId = read(place(9, null, null, Map.of(1L, 1))).get("orderId").asText();

        mvc.perform(asUser(9, get("/api/orders/{id}", orderId))).andExpect(status().isOk());
        mvc.perform(asUser(10, get("/api/orders/{id}", orderId))).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER-004"));
    }

    @Test
    void productServiceDownMeansNoOrder() throws Exception {
        services.stubFor(WireMock.get(urlPathEqualTo("/internal/products")).willReturn(serverError()));

        place(11, null, null, Map.of(1L, 1)).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("ORDER-005"));
        mvc.perform(asUser(11, get("/api/orders"))).andExpect(jsonPath("$.page.totalElements").value(0));
    }

    private ResultActions place(long userId, String idempotencyKey, Long couponId, Map<Long, Integer> items) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("paymentToken", "tok_approve");
        body.put("items", items.entrySet().stream().map(e -> Map.of("productId", e.getKey(), "quantity", e.getValue())).toList());
        if (couponId != null) {
            body.put("userCouponId", couponId);
        }
        MockHttpServletRequestBuilder request = asUser(userId, post("/api/orders"))
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey);
        }
        return mvc.perform(request);
    }

    private JsonNode read(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private static MockHttpServletRequestBuilder asUser(long userId, MockHttpServletRequestBuilder request) {
        return request.header(AuthHeaders.USER_ID, userId).header(AuthHeaders.USER_ROLES, "USER");
    }
}
