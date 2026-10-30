package com.repopilot.common.config;

import com.repopilot.common.telemetry.W3CTraceContextClientHttpRequestInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

@Configuration
public class AppConfig {

    @Bean
    public RestTemplate restTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10000); // 10.0 seconds connect timeout
        factory.setReadTimeout(180000);   // 180 seconds (3 mins) socket read timeout for AI embeddings & generation
        RestTemplate restTemplate = new RestTemplate(factory);
        restTemplate.getInterceptors().add(new W3CTraceContextClientHttpRequestInterceptor());
        return restTemplate;
    }
}
