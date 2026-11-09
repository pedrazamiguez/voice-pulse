package com.voicepulse.conversation.infrastructure.kafka.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuration properties mapped to {@code voicepulse.conversation.kafka}. */
@ConfigurationProperties(prefix = "voicepulse.conversation.kafka")
public record ConversationKafkaProperties(
        boolean enabled, String consumerGroup, int concurrency, String autoOffsetReset) {

    public static final String DEFAULT_GROUP_ID = "conversation-engine-workers";
    public static final int DEFAULT_CONCURRENCY = 3;
    public static final String DEFAULT_AUTO_OFFSET_RESET = "earliest";

    public ConversationKafkaProperties {
        consumerGroup =
                (consumerGroup != null && !consumerGroup.isBlank())
                        ? consumerGroup
                        : DEFAULT_GROUP_ID;
        concurrency = concurrency > 0 ? concurrency : DEFAULT_CONCURRENCY;
        autoOffsetReset =
                (autoOffsetReset != null && !autoOffsetReset.isBlank())
                        ? autoOffsetReset
                        : DEFAULT_AUTO_OFFSET_RESET;
    }
}
