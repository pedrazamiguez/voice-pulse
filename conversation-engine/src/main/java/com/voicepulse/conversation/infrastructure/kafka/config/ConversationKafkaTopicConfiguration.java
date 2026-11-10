package com.voicepulse.conversation.infrastructure.kafka.config;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.TopicConfig;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/** Spring Kafka programmatic topic provisioning configuration for Conversation Engine. */
@Configuration
@EnableConfigurationProperties(KafkaTopicProperties.class)
@ConditionalOnProperty(
        prefix = "voicepulse.conversation.kafka",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
@RequiredArgsConstructor
public class ConversationKafkaTopicConfiguration {

    private final @NonNull KafkaTopicProperties topicProperties;

    @Bean
    public NewTopic telephonyCallsRawTopic() {
        return buildTopic(topicProperties.raw());
    }

    @Bean
    public NewTopic telephonyCallsControlTopic() {
        return buildTopic(topicProperties.control());
    }

    @Bean
    public NewTopic telephonyCallsDlqTopic() {
        return buildTopic(topicProperties.dlq());
    }

    private static NewTopic buildTopic(KafkaTopicProperties.TopicConfig config) {
        TopicBuilder builder =
                TopicBuilder.name(config.name())
                        .partitions(config.partitions())
                        .replicas(config.replicationFactor())
                        .config(
                                TopicConfig.RETENTION_MS_CONFIG,
                                String.valueOf(config.retention().toMillis()));

        if (config.segmentBytes() != null && config.segmentBytes() > 0) {
            builder.config(TopicConfig.SEGMENT_BYTES_CONFIG, String.valueOf(config.segmentBytes()));
        }

        return builder.build();
    }
}
