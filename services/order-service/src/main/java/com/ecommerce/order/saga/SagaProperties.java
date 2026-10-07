package com.ecommerce.order.saga;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * @param stepTimeout  how long to wait for a participant's reply before resending
 * @param maxAttempts  sends per step (first send included) before compensating
 * @param pollInterval how often the timeout job looks for overdue sagas
 */
@ConfigurationProperties("ecommerce.saga")
public record SagaProperties(
        @DefaultValue("10s") Duration stepTimeout,
        @DefaultValue("3") int maxAttempts,
        @DefaultValue("2s") Duration pollInterval
) {
}
