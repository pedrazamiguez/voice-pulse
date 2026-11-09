package com.voicepulse.conversation.infrastructure.kafka.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuration properties defining Kafka broker topic topologies for Conversation Engine. */
@ConfigurationProperties(prefix = "voicepulse.kafka.topics")
public record KafkaTopicProperties(TopicConfig raw, TopicConfig control, TopicConfig dlq) {

    public record TopicConfig(
            String name,
            int partitions,
            short replicationFactor,
            Duration retention,
            Long segmentBytes) {}
}
