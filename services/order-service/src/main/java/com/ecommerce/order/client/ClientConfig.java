package com.ecommerce.order.client;

import com.ecommerce.common.error.ErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.web.client.RestClientBuilderConfigurer;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

import java.io.IOException;
import java.net.http.HttpClient;

/**
 * Declarative clients with Spring's HTTP interfaces (@HttpExchange) on RestClient, instead of OpenFeign,
 * which Spring Cloud now keeps in maintenance mode.
 */
@Configuration
public class ClientConfig {

    @Bean
    @LoadBalanced
    RestClient.Builder loadBalancedRestClientBuilder(RestClientBuilderConfigurer configurer) {
        // Start from Boot's configured builder so the clients share the application's ObjectMapper.
        return configurer.configure(RestClient.builder());
    }

    @Bean
    ProductClient productClient(@LoadBalanced RestClient.Builder builder, ClientProperties properties, ObjectMapper objectMapper) {
        return create(ProductClient.class, "product-service", builder, properties.product(), objectMapper);
    }

    private static <T> T create(Class<T> type, String service, RestClient.Builder builder,
                                ClientProperties.Target target, ObjectMapper objectMapper) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(target.connectTimeout()).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(target.readTimeout());

        RestClient client = builder.clone()
                .baseUrl(target.url() != null ? target.url() : "http://" + service)
                .requestFactory(requestFactory)
                // 4xx: a business answer ("no stock"), passed on with its code. 5xx and timeouts keep
                // Spring's exceptions and are treated as "the service is unavailable".
                .defaultStatusHandler(HttpStatusCode::is4xxClientError, (request, response) -> {
                    throw new DownstreamException(service, response.getStatusCode(), readCode(objectMapper, response.getBody().readAllBytes()));
                })
                .build();
        return HttpServiceProxyFactory.builderFor(RestClientAdapter.create(client)).build().createClient(type);
    }

    private static String readCode(ObjectMapper objectMapper, byte[] body) {
        try {
            return objectMapper.readValue(body, ErrorResponse.class).code();
        } catch (IOException | RuntimeException e) {
            return "UNKNOWN";
        }
    }
}
