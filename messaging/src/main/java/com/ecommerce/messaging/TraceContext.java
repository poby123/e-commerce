package com.ecommerce.messaging;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;

import java.util.HashMap;
import java.util.Map;

/** The current span as a W3C traceparent header value ("" when there is no span). */
public class TraceContext {

    private final Tracer tracer;
    private final Propagator propagator;

    public TraceContext(Tracer tracer, Propagator propagator) {
        this.tracer = tracer;
        this.propagator = propagator;
    }

    public String currentTraceparent() {
        Span span = tracer.currentSpan();
        if (span == null) {
            return "";
        }
        Map<String, String> carrier = new HashMap<>();
        propagator.inject(span.context(), carrier, Map::put);
        return carrier.getOrDefault("traceparent", "");
    }
}
