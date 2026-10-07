package com.ecommerce.search.index;

import co.elastic.clients.json.JsonpMapper;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ElasticsearchConfig {

    /**
     * The client's default mapper does not know java.time; use a copy of Boot's (ISO-8601 dates), leaving nulls
     * out as the client expects.
     */
    @Bean
    JsonpMapper jsonpMapper(ObjectMapper objectMapper) {
        return new JacksonJsonpMapper(objectMapper.copy().setSerializationInclusion(JsonInclude.Include.NON_NULL));
    }
}
