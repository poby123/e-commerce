package com.ecommerce.order;

import com.ecommerce.common.error.BusinessException;
import com.ecommerce.order.api.OrderDtos.Line;
import com.ecommerce.order.api.OrderDtos.PlaceOrderRequest;
import com.ecommerce.order.application.OrderService;
import com.github.tomakehurst.wiremock.client.WireMock;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.serverError;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductLookupResilienceTest extends OrderTestSupport {

    @Autowired
    OrderService orderService;
    @Autowired
    CircuitBreakerRegistry circuitBreakers;

    @AfterEach
    void closeCircuit() {
        circuitBreakers.circuitBreaker("product").reset();
    }

    @Test
    void oneServerErrorIsRetried() {
        services.stubFor(WireMock.get(urlPathEqualTo("/internal/products")).inScenario("flaky").whenScenarioStateIs(STARTED)
                .willReturn(serverError()).willSetStateTo("recovered"));
        services.stubFor(WireMock.get(urlPathEqualTo("/internal/products")).inScenario("flaky").whenScenarioStateIs("recovered")
                .willReturn(okJson("[{\"id\": 1, \"name\": \"Mug\", \"price\": 12000, \"status\": \"ON_SALE\"}]")));

        assertThat(place()).isNotNull();
        services.verify(2, getRequestedFor(urlPathEqualTo("/internal/products")));
    }

    @Test
    void failingProductServiceOpensTheCircuitAndOrdersFailFast() {
        services.stubFor(WireMock.get(urlPathEqualTo("/internal/products")).willReturn(serverError()));

        for (int i = 0; i < 5; i++) {                       // 5 orders x 2 attempts = 10 failed calls
            assertThatThrownBy(this::place).isInstanceOf(BusinessException.class);
        }
        assertThat(circuitBreakers.circuitBreaker("product").getState()).isEqualTo(CircuitBreaker.State.OPEN);

        int callsBefore = services.findAll(getRequestedFor(urlPathEqualTo("/internal/products"))).size();
        long started = System.nanoTime();
        assertThatThrownBy(this::place).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.errorCode().code()).isEqualTo("ORDER-005"));
        assertThat((System.nanoTime() - started) / 1_000_000).isLessThan(50);
        // Rejected without calling product-service at all.
        assertThat(services.findAll(getRequestedFor(urlPathEqualTo("/internal/products")))).hasSize(callsBefore);
    }

    @Test
    void businessErrorsDoNotOpenTheCircuit() {
        services.stubFor(WireMock.get(urlPathEqualTo("/internal/products")).willReturn(aResponse().withStatus(400)
                .withHeader("Content-Type", "application/json").withBody("{\"code\": \"PRODUCT-002\"}")));

        for (int i = 0; i < 15; i++) {
            assertThatThrownBy(this::place).isInstanceOf(BusinessException.class);
        }
        assertThat(circuitBreakers.circuitBreaker("product").getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        // 4xx is not retried either.
        services.verify(15, getRequestedFor(urlPathEqualTo("/internal/products")));
    }

    @Test
    void noInstanceInTheRegistryIsAnOutageNotAServerError() {
        var catalog = new com.ecommerce.order.client.ProductCatalog(ids -> {
            throw new IllegalStateException("No instances available for product-service");
        });
        assertThatThrownBy(() -> catalog.findAll(List.of(1L)))
                .isInstanceOf(org.springframework.web.client.ResourceAccessException.class);
    }

    private String place() {
        return orderService.place(77, null, new PlaceOrderRequest(List.of(new Line(1L, 1)), null, "tok_approve")).getOrderId();
    }
}
