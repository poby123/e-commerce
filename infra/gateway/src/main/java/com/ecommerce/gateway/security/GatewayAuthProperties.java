package com.ecommerce.gateway.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

/**
 * @param jwkSetUri   where the public keys live; "user-service" is resolved through Eureka
 * @param issuer      expected "iss" claim
 * @param publicPaths paths reachable without an access token
 */
@ConfigurationProperties("gateway.auth")
public record GatewayAuthProperties(
        @DefaultValue("http://user-service/.well-known/jwks.json") String jwkSetUri,
        @DefaultValue("ecommerce-auth") String issuer,
        @DefaultValue({"/api/auth/**", "/actuator/health/**", "/actuator/info"}) List<String> publicPaths
) {
}
