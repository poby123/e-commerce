package com.ecommerce.gateway;

import com.ecommerce.common.web.AuthHeaders;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

import java.util.List;

import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity;

/**
 * Routes point at a local echo handler (forward:) that returns the headers a downstream service
 * would receive, so the test checks exactly what the gateway passes on.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "spring.cloud.config.enabled=false",
                "eureka.client.enabled=false",
                "spring.cloud.gateway.server.webflux.routes[0].id=users",
                "spring.cloud.gateway.server.webflux.routes[0].uri=forward:/test/echo",
                "spring.cloud.gateway.server.webflux.routes[0].predicates[0]=Path=/api/users/**",
                "spring.cloud.gateway.server.webflux.routes[1].id=auth",
                "spring.cloud.gateway.server.webflux.routes[1].uri=forward:/test/echo",
                "spring.cloud.gateway.server.webflux.routes[1].predicates[0]=Path=/api/auth/**",
                "spring.cloud.gateway.server.webflux.routes[2].id=products",
                "spring.cloud.gateway.server.webflux.routes[2].uri=forward:/test/echo",
                "spring.cloud.gateway.server.webflux.routes[2].predicates[0]=Path=/api/products/**",
        })
class GatewayAuthenticationTest {

    @Autowired
    ApplicationContext context;

    WebTestClient client;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToApplicationContext(context).apply(springSecurity()).configureClient().build();
    }

    @Test
    void protectedRouteWithoutTokenReturns401Json() {
        client.get().uri("/api/users/me")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.code").isEqualTo("COMMON-401");
    }

    @Test
    void protectedRouteWithInvalidTokenReturns401() {
        client.get().uri("/api/users/me")
                .header("Authorization", "Bearer not-a-jwt")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void verifiedTokenBecomesUserHeaders() {
        client.mutateWith(mockJwt().jwt(jwt -> jwt.subject("42").claim("roles", List.of("USER"))))
                .get().uri("/api/users/me")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$['" + AuthHeaders.USER_ID + "']").isEqualTo("42")
                .jsonPath("$['" + AuthHeaders.USER_ROLES + "']").isEqualTo("USER");
    }

    @Test
    void spoofedUserHeaderIsReplacedByTokenSubject() {
        client.mutateWith(mockJwt().jwt(jwt -> jwt.subject("42")))
                .get().uri("/api/users/me")
                .header(AuthHeaders.USER_ID, "1")
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$['" + AuthHeaders.USER_ID + "']").isEqualTo("42");
    }

    @Test
    void spoofedUserHeaderIsStrippedOnPublicRoute() {
        client.get().uri("/api/auth/anything")
                .header(AuthHeaders.USER_ID, "1")
                .header(AuthHeaders.USER_ROLES, "ADMIN")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$['" + AuthHeaders.USER_ID + "']").doesNotExist()
                .jsonPath("$['" + AuthHeaders.USER_ROLES + "']").doesNotExist();
    }

    @Test
    void publicPathCanBeLimitedToOneMethod() {
        client.get().uri("/api/products/1").exchange().expectStatus().isOk();
        client.post().uri("/api/products").exchange().expectStatus().isUnauthorized();
    }

    @TestConfiguration
    static class EchoHandler {

        @Bean
        RouterFunction<ServerResponse> echoHeaders() {
            return RouterFunctions.route()
                    .GET("/test/echo", request ->
                            ServerResponse.ok().bodyValue(request.headers().asHttpHeaders().toSingleValueMap()))
                    .build();
        }
    }
}
