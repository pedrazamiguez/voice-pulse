package com.voicepulse.conversation.infrastructure.mcp.config;

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
 * Spring configuration provisioning the Virtual Thread executor, HTTP client, and JSON codec for
 * outbound Model Context Protocol (MCP) tool servers.
 */
@Configuration
@EnableConfigurationProperties(McpClientProperties.class)
public class McpClientConfiguration {

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(name = "mcpVirtualThreadExecutor")
    public ExecutorService mcpVirtualThreadExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    @Bean
    @ConditionalOnMissingBean
    public HttpClient mcpHttpClient(
            McpClientProperties properties, ExecutorService mcpVirtualThreadExecutor) {
        return HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .executor(mcpVirtualThreadExecutor)
                .build();
    }

    @Bean
    @ConditionalOnMissingBean
    public ObjectMapper objectMapper() {
        return KafkaJsonCodecProvider.objectMapper();
    }
}
