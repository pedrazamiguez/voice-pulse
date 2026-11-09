package com.voicepulse.conversation.infrastructure.kafka.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Configuration properties mapped to {@code voicepulse.conversation.kafka}. */
@ConfigurationProperties(prefix = "voicepulse.conversation.kafka")
public record ConversationKafkaProperties(
        boolean enabled,
        String consumerGroup,
        int concurrency,
        String autoOffsetReset,
        @DefaultValue("8") int workerPoolSize) {

    public static final int DEFAULT_WORKER_POOL_SIZE = 8;

    public ConversationKafkaProperties {
        if (workerPoolSize <= 0) {
            workerPoolSize = DEFAULT_WORKER_POOL_SIZE;
        }
    }
}
