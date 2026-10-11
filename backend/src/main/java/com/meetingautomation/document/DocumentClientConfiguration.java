package com.meetingautomation.document;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/** Shared HTTP client builder for document provider adapters. */
@Configuration(proxyBeanMethods = false)
public class DocumentClientConfiguration {
    @Bean
    RestClient.Builder documentRestClientBuilder() {
        return RestClient.builder().requestFactory(new SafeDocumentClientHttpRequestFactory());
    }
}
