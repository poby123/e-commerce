package com.ecommerce.gateway.filter;

import com.ecommerce.common.web.AuthHeaders;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.security.Principal;
import java.util.List;
import java.util.Optional;

/**
 * Turns the verified token into plain headers for downstream services.
 * Client-sent copies are always removed first, so "X-User-Id: 1" from outside never reaches a service.
 */
@Component
public class UserContextFilter implements GlobalFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return exchange.getPrincipal()
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty())
                .flatMap(principal -> chain.filter(withUserHeaders(exchange, principal)));
    }

    private ServerWebExchange withUserHeaders(ServerWebExchange exchange, Optional<Principal> principal) {
        ServerHttpRequest request = exchange.getRequest().mutate()
                .headers(headers -> {
                    headers.remove(AuthHeaders.USER_ID);
                    headers.remove(AuthHeaders.USER_ROLES);
                    principal.filter(JwtAuthenticationToken.class::isInstance)
                            .map(JwtAuthenticationToken.class::cast)
                            .ifPresent(auth -> {
                                headers.set(AuthHeaders.USER_ID, auth.getToken().getSubject());
                                List<String> roles = auth.getToken().getClaimAsStringList("roles");
                                if (roles != null && !roles.isEmpty()) {
                                    headers.set(AuthHeaders.USER_ROLES, String.join(",", roles));
                                }
                            });
                })
                .build();
        return exchange.mutate().request(request).build();
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
