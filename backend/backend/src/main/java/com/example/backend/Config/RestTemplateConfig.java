package com.example.backend.Config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

@Configuration
public class RestTemplateConfig {

    /*@Bean  // Declares RestTemplate as a Spring bean
    public RestTemplate restTemplate() {
        return new RestTemplate();  // Returns a new RestTemplate instance
    }*/

    @Bean
    public RestTemplate restTemplate() {
        // Use HttpComponentsClientHttpRequestFactory so PATCH is supported
        HttpComponentsClientHttpRequestFactory factory = new HttpComponentsClientHttpRequestFactory();

        return new RestTemplate(factory);
    }

}
