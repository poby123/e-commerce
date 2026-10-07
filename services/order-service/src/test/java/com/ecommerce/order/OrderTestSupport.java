package com.ecommerce.order;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;

import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

/**
 * Real MySQL; product-service is a WireMock stub reached through the load balancer (a static instance list
 * instead of Eureka). Kafka is not started: commands are read from the outbox table, replies are injected.
 */
@SpringBootTest(properties = {
        "spring.cloud.config.enabled=false",
        "eureka.client.enabled=false",
        "spring.kafka.listener.auto-startup=false",
        "spring.kafka.admin.auto-create=false",
        "ecommerce.saga.timeout-job.enabled=false",
        "ecommerce.saga.max-attempts=2",
        "ecommerce.clients.product.read-timeout=500ms",
})
@AutoConfigureMockMvc
@Import(OrderTestSupport.Containers.class)
abstract class OrderTestSupport {

    @TestConfiguration(proxyBeanMethods = false)
    static class Containers {
        @Bean
        @ServiceConnection
        MySQLContainer<?> mysql() {
            return new MySQLContainer<>("mysql:8.4");
        }
    }

    /**
     * One stub server for the whole JVM. Spring caches the application context across test classes with the
     * same configuration, so the URL registered below must stay valid for all of them; a per-class
     * WireMockExtension would restart on a new port and leave the cached context calling a dead one.
     */
    static final WireMockServer services = new WireMockServer(wireMockConfig().dynamicPort());

    static {
        services.start();
    }

    @DynamicPropertySource
    static void serviceInstances(DynamicPropertyRegistry registry) {
        registry.add("spring.cloud.discovery.client.simple.instances.product-service[0].uri", services::baseUrl);
    }

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void products() {
        services.resetAll();
        services.stubFor(WireMock.get(urlPathEqualTo("/internal/products")).willReturn(okJson("""
                [{"id": 1, "name": "Mug", "price": 12000, "status": "ON_SALE"},
                 {"id": 2, "name": "Coaster", "price": 3000, "status": "ON_SALE"},
                 {"id": 3, "name": "Old lamp", "price": 30000, "status": "SUSPENDED"}]
                """)));
    }

    /** "topic:Type" of every message the saga wrote for this order, in order. */
    List<String> sent(String orderId) {
        return jdbc.queryForList("SELECT CONCAT(aggregate_type, ':', type) FROM outbox WHERE aggregate_id = ? ORDER BY seq",
                String.class, orderId);
    }

    String sentField(String orderId, String type, String field) {
        return jdbc.queryForObject("SELECT JSON_UNQUOTE(JSON_EXTRACT(payload, '$." + field + "')) FROM outbox WHERE aggregate_id = ? AND type = ? ORDER BY seq DESC LIMIT 1",
                String.class, orderId, type);
    }
}
