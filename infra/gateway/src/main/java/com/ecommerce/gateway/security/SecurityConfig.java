package com.ecommerce.gateway.security;

import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.security.web.server.savedrequest.NoOpServerRequestCache;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * The gateway is the only place that verifies access tokens. It validates the RS256 signature with
 * user-service's public keys (JWKS), so it never holds a secret that could mint tokens.
 */
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    @Bean
    SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http,
                                                  GatewayAuthProperties properties,
                                                  JsonErrorHandlers errorHandlers) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .logout(ServerHttpSecurity.LogoutSpec::disable)
                // Stateless: no session, no saved request.
                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
                .requestCache(cache -> cache.requestCache(NoOpServerRequestCache.getInstance()))
                .authorizeExchange(exchange -> exchange
                        .pathMatchers(properties.publicPaths().toArray(String[]::new)).permitAll()
                        .anyExchange().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()))
                        .authenticationEntryPoint(errorHandlers))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(errorHandlers)
                        .accessDeniedHandler(errorHandlers))
                .build();
    }

    @Bean
    @LoadBalanced
    WebClient.Builder loadBalancedWebClientBuilder() {
        return WebClient.builder();
    }

    @Bean
    ReactiveJwtDecoder jwtDecoder(@LoadBalanced WebClient.Builder webClientBuilder, GatewayAuthProperties properties) {
        // Keys are cached; an unknown "kid" (user-service rotated its key) triggers a refetch.
        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder.withJwkSetUri(properties.jwkSetUri())
                .webClient(webClientBuilder.build())
                .build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(properties.issuer()));
        return decoder;
    }

    private ReactiveJwtAuthenticationConverterAdapter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("roles");
        authorities.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return new ReactiveJwtAuthenticationConverterAdapter(converter);
    }
}
