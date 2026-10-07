package com.ecommerce.order.client;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Base URLs use service names; the load-balanced client resolves them (Eureka here, a static list in tests).
 * Every call has a timeout: without one, a hung dependency holds a thread forever.
 */
@ConfigurationProperties("ecommerce.clients")
public record ClientProperties(
        @DefaultValue Target product,
        @DefaultValue Target inventory
) {

    public record Target(
            String url,
            @DefaultValue("1s") Duration connectTimeout,
            @DefaultValue("3s") Duration readTimeout
    ) {
    }
}
