package com.voicepulse.conversation.infrastructure.ai.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voicepulse.shared.infrastructure.kafka.serializer.KafkaJsonCodecProvider;
import java.net.http.HttpClient;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring configuration provisioning the Virtual Thread executor and HTTP client for live LLM
 * conversational inference.
 */
@Configuration
@EnableConfigurationProperties(AiInferenceProperties.class)
public class AiInferenceConfiguration {

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(name = "aiVirtualThreadExecutor")
    public ExecutorService aiVirtualThreadExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    @Bean
    @ConditionalOnMissingBean(name = "aiHttpClient")
    public HttpClient aiHttpClient(
            AiInferenceProperties properties, ExecutorService aiVirtualThreadExecutor) {
        return HttpClient.newBuilder()
                .connectTimeout(properties.timeout())
                .executor(aiVirtualThreadExecutor)
                .build();
    }

    @Bean
    @ConditionalOnMissingBean
    public ObjectMapper objectMapper() {
        return KafkaJsonCodecProvider.objectMapper();
    }
}
