package com.ecommerce.gateway.security;

import com.ecommerce.common.error.CommonErrorCode;
import com.ecommerce.common.error.ErrorResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** 401/403 in the same JSON format the services use, instead of Spring Security's empty bodies. */
@Component
public class JsonErrorHandlers implements ServerAuthenticationEntryPoint, ServerAccessDeniedHandler {

    private final ObjectMapper objectMapper;

    public JsonErrorHandlers(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<Void> commence(ServerWebExchange exchange, AuthenticationException ex) {
        return write(exchange, CommonErrorCode.UNAUTHENTICATED);
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, AccessDeniedException denied) {
        return write(exchange, CommonErrorCode.FORBIDDEN);
    }

    private Mono<Void> write(ServerWebExchange exchange, CommonErrorCode errorCode) {
        var response = exchange.getResponse();
        response.setStatusCode(errorCode.status());
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        try {
            byte[] body = objectMapper.writeValueAsBytes(ErrorResponse.of(errorCode));
            return response.writeWith(Mono.just(response.bufferFactory().wrap(body)));
        } catch (JsonProcessingException e) {
            return Mono.error(e);
        }
    }
}
