package com.ecommerce.gateway.resilience;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.util.Optional;

/** Who a rate limit counts against. Referenced from route config as #{@clientIpKeyResolver} / #{@userKeyResolver}. */
@Configuration
public class RateLimitKeys {

    /**
     * Before login there is no user, so the client address. Behind a load balancer this must read
     * X-Forwarded-For with a trusted hop count instead (XForwardedRemoteAddressResolver).
     * Primary: the default for a RequestRateLimiter route that names no key-resolver.
     */
    @Bean
    @Primary
    KeyResolver clientIpKeyResolver() {
        return exchange -> Mono.just(Optional.ofNullable(exchange.getRequest().getRemoteAddress())
                .map(InetSocketAddress::getAddress)
                .map(address -> "ip:" + address.getHostAddress())
                .orElse("ip:unknown"));
    }

    /** After login: the verified user id, so one user cannot use up another's budget. */
    @Bean
    KeyResolver userKeyResolver() {
        return exchange -> exchange.getPrincipal()
                .filter(JwtAuthenticationToken.class::isInstance)
                .map(p -> "user:" + ((JwtAuthenticationToken) p).getToken().getSubject())
                .defaultIfEmpty("user:anonymous");
    }
}
