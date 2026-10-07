package com.ecommerce.gateway;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.testcontainers.containers.GenericContainer;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "spring.cloud.config.enabled=false",
                "eureka.client.enabled=false",
                // Login-like route: 3 requests of burst, then 1 per second.
                "spring.cloud.gateway.server.webflux.routes[0].id=login",
                "spring.cloud.gateway.server.webflux.routes[0].uri=forward:/test/ok",
                "spring.cloud.gateway.server.webflux.routes[0].predicates[0]=Path=/api/auth/login",
                "spring.cloud.gateway.server.webflux.routes[0].filters[0].name=RequestRateLimiter",
                "spring.cloud.gateway.server.webflux.routes[0].filters[0].args[redis-rate-limiter.replenishRate]=1",
                "spring.cloud.gateway.server.webflux.routes[0].filters[0].args[redis-rate-limiter.burstCapacity]=3",
                "spring.cloud.gateway.server.webflux.routes[0].filters[0].args[key-resolver]=#{@clientIpKeyResolver}",
                // A service nobody listens on.
                "spring.cloud.gateway.server.webflux.routes[1].id=dead",
                "spring.cloud.gateway.server.webflux.routes[1].uri=http://localhost:1",
                "spring.cloud.gateway.server.webflux.routes[1].predicates[0]=Path=/api/products/**",
                "spring.cloud.gateway.server.webflux.routes[1].filters[0].name=CircuitBreaker",
                "spring.cloud.gateway.server.webflux.routes[1].filters[0].args.name=product-service",
                "spring.cloud.gateway.server.webflux.routes[1].filters[0].args.fallbackUri=forward:/fallback/unavailable",
        })
@Import(GatewayResilienceTest.Infra.class)
class GatewayResilienceTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class Infra {
        @Bean
        @ServiceConnection(name = "redis")
        GenericContainer<?> redis() {
            return new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);
        }

        @Bean
        RouterFunction<ServerResponse> ok() {
            return RouterFunctions.route().GET("/test/ok", r -> ServerResponse.ok().bodyValue("ok")).build();
        }
    }

    @Autowired
    ApplicationContext context;

    WebTestClient client;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToApplicationContext(context).apply(springSecurity()).configureClient().build();
    }

    @Test
    void loginAttemptsBeyondTheBurstAreRejectedWith429() {
        List<Integer> statuses = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            statuses.add(client.get().uri("/api/auth/login").exchange().returnResult(String.class).getStatus().value());
        }
        assertThat(statuses.subList(0, 3)).containsOnly(200);
        assertThat(statuses.subList(3, 6)).contains(429);
    }

    @Test
    void unreachableServiceGetsTheJsonFallbackInsteadOfAnError() {
        client.get().uri("/api/products/1")
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectBody().jsonPath("$.code").isEqualTo("GATEWAY-503");
    }
}
