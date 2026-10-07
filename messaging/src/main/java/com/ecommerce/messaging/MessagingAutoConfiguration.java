package com.ecommerce.messaging;

import com.ecommerce.messaging.contract.Topics;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

import java.io.UncheckedIOException;

@AutoConfiguration(after = {JdbcTemplateAutoConfiguration.class, KafkaAutoConfiguration.class})
@ConditionalOnClass(JdbcTemplate.class)
public class MessagingAutoConfiguration {

    @Bean
    @ConditionalOnBean(JdbcTemplate.class)
    Outbox outbox(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper, ObjectProvider<TraceContext> traceContext) {
        TraceContext context = traceContext.getIfAvailable();
        return new Outbox(jdbcTemplate, objectMapper, context != null ? context::currentTraceparent : () -> "");
    }

    /** Only when Micrometer Tracing is on the classpath. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "io.micrometer.tracing.Tracer")
    static class Tracing {
        @Bean
        @ConditionalOnBean(type = {"io.micrometer.tracing.Tracer", "io.micrometer.tracing.propagation.Propagator"})
        TraceContext traceContext(io.micrometer.tracing.Tracer tracer, io.micrometer.tracing.propagation.Propagator propagator) {
            return new TraceContext(tracer, propagator);
        }
    }

    @Bean
    @ConditionalOnBean(JdbcTemplate.class)
    ProcessedMessages processedMessages(JdbcTemplate jdbcTemplate) {
        return new ProcessedMessages(jdbcTemplate);
    }

    /**
     * Boot wires this into the default listener container factory. A failing message is retried with backoff
     * (a DB deadlock or optimistic-lock conflict usually passes on retry), then parked on "topic.DLT" so one bad
     * message does not block its partition. Malformed messages go to the DLT at once.
     */
    @Bean
    @ConditionalOnBean(KafkaTemplate.class)
    CommonErrorHandler kafkaErrorHandler(KafkaTemplate<Object, Object> kafkaTemplate) {
        ExponentialBackOff backOff = new ExponentialBackOff(500, 2.0);
        backOff.setMaxElapsedTime(10_000);
        DefaultErrorHandler handler = new DefaultErrorHandler(new DeadLetterPublishingRecoverer(kafkaTemplate), backOff);
        handler.addNotRetryableExceptions(IllegalArgumentException.class, UncheckedIOException.class);
        return handler;
    }

    /** Topics with 3 partitions: ordering is per key (saga), so partitions give parallelism without losing it. */
    @Bean
    @ConditionalOnBean(KafkaAdmin.class)
    KafkaAdmin.NewTopics ecommerceTopics() {
        return new KafkaAdmin.NewTopics(Topics.ALL.stream()
                .map(name -> TopicBuilder.name(name).partitions(3).replicas(1).build())
                .toArray(NewTopic[]::new));
    }
}
