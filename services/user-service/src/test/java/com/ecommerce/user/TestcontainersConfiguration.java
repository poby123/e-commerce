package com.ecommerce.user;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;

/** The same MySQL and Redis versions as compose.yaml; containers are shared across test classes. */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    MySQLContainer<?> mysql() {
        return new MySQLContainer<>("mysql:8.4");
    }

    @Bean
    @ServiceConnection(name = "redis")
    GenericContainer<?> redis() {
        return new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);
    }
}
