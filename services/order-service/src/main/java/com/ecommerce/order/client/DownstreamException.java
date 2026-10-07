package com.ecommerce.order.client;

import org.springframework.http.HttpStatusCode;

/** A 4xx from another service, with its error code (e.g. INVENTORY-001) so the caller can decide what it means. */
public class DownstreamException extends RuntimeException {

    private final HttpStatusCode status;
    private final String code;

    public DownstreamException(String service, HttpStatusCode status, String code) {
        super(service + " responded " + status.value() + " " + code);
        this.status = status;
        this.code = code;
    }

    public HttpStatusCode status() {
        return status;
    }

    public String code() {
        return code;
    }
}
