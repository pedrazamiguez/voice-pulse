package com.voicepulse.routing.infrastructure.persistence.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voicepulse.shared.infrastructure.kafka.serializer.KafkaJsonCodecProvider;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring configuration provisioning the Java 21 Virtual Thread executor and fallback JSON codec for
 * blocking PostgreSQL JDBC persistence operations.
 */
@Configuration
public class PersistenceConfiguration {

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(name = "jdbcVirtualThreadExecutor")
    public ExecutorService jdbcVirtualThreadExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    @Bean
    @ConditionalOnMissingBean
    public ObjectMapper objectMapper() {
        return KafkaJsonCodecProvider.objectMapper();
    }
}
