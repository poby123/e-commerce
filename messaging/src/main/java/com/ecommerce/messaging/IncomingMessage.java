package com.ecommerce.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/** A message read from Kafka: the outbox row's id and type (from headers), key and JSON payload. */
public record IncomingMessage(String id, String type, String key, String payload) {

    public static IncomingMessage from(ConsumerRecord<String, String> record) {
        String id = header(record, "id");
        String type = header(record, "type");
        if (id == null || type == null) {
            throw new IllegalArgumentException("Message without id/type headers at " + record.topic() + "-"
                    + record.partition() + "@" + record.offset());
        }
        return new IncomingMessage(id, type, record.key(), record.value());
    }

    public <T> T payload(ObjectMapper objectMapper, Class<T> type) {
        try {
            return objectMapper.readValue(payload, type);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + this.type + " " + id, e);
        }
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        if (header == null) {
            return null;
        }
        String value = new String(header.value(), StandardCharsets.UTF_8);
        // Kafka Connect may write string headers JSON-quoted.
        return value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"") ? value.substring(1, value.length() - 1) : value;
    }
}
