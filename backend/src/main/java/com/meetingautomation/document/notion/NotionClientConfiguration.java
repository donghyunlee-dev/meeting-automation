package com.meetingautomation.document.notion;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
class NotionClientConfiguration {
    @Bean
    RestClient.Builder notionRestClientBuilder() {
        return RestClient.builder();
    }
}
