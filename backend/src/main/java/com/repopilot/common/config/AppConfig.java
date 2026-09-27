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
        factory.setConnectTimeout(2000); // 2.0 seconds connect timeout per attempt
        factory.setReadTimeout(4000);    // 4.0 seconds socket read timeout per attempt
        RestTemplate restTemplate = new RestTemplate(factory);
        restTemplate.getInterceptors().add(new W3CTraceContextClientHttpRequestInterceptor());
        return restTemplate;
    }
}
