package com.voicepulse.conversation.infrastructure.kafka.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuration properties mapped to {@code voicepulse.conversation.kafka}. */
@ConfigurationProperties(prefix = "voicepulse.conversation.kafka")
public record ConversationKafkaProperties(
        boolean enabled, String consumerGroup, int concurrency, String autoOffsetReset) {}
