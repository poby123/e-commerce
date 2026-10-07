package com.ecommerce.gateway.resilience;

import com.ecommerce.common.error.ErrorResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Target of the routes' CircuitBreaker fallbackUri: a service is down, too slow, or its circuit is open.
 * The client gets the usual error format at once instead of waiting for a timeout.
 */
@RestController
public class FallbackController {

    @RequestMapping("/fallback/unavailable")
    public Mono<ResponseEntity<ErrorResponse>> unavailable() {
        return Mono.just(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ErrorResponse.of(GatewayErrorCode.SERVICE_UNAVAILABLE)));
    }
}
