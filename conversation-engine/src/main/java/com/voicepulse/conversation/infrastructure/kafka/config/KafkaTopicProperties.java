package com.voicepulse.conversation.infrastructure.kafka.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuration properties defining Kafka broker topic topologies for Conversation Engine. */
@ConfigurationProperties(prefix = "voicepulse.kafka.topics")
public record KafkaTopicProperties(TopicConfig raw, TopicConfig control, TopicConfig dlq) {

    public static final String DEFAULT_RAW_TOPIC = "telephony.calls.raw";
    public static final int DEFAULT_RAW_PARTITIONS = 12;
    public static final Duration DEFAULT_RAW_RETENTION = Duration.ofHours(1);
    public static final long DEFAULT_RAW_SEGMENT_BYTES = 52_428_800L; // 50 MB

    public static final String DEFAULT_CONTROL_TOPIC = "telephony.calls.control";
    public static final int DEFAULT_CONTROL_PARTITIONS = 12;
    public static final Duration DEFAULT_CONTROL_RETENTION = Duration.ofHours(1);

    public static final String DEFAULT_DLQ_TOPIC = "telephony.calls.dlq";
    public static final int DEFAULT_DLQ_PARTITIONS = 3;
    public static final Duration DEFAULT_DLQ_RETENTION = Duration.ofDays(14);

    public KafkaTopicProperties {
        raw = raw != null ? raw : defaultRawConfig();
        control = control != null ? control : defaultControlConfig();
        dlq = dlq != null ? dlq : defaultDlqConfig();
    }

    public static TopicConfig defaultRawConfig() {
        return new TopicConfig(
                DEFAULT_RAW_TOPIC,
                DEFAULT_RAW_PARTITIONS,
                (short) 1,
                DEFAULT_RAW_RETENTION,
                DEFAULT_RAW_SEGMENT_BYTES);
    }

    public static TopicConfig defaultControlConfig() {
        return new TopicConfig(
                DEFAULT_CONTROL_TOPIC,
                DEFAULT_CONTROL_PARTITIONS,
                (short) 1,
                DEFAULT_CONTROL_RETENTION,
                null);
    }

    public static TopicConfig defaultDlqConfig() {
        return new TopicConfig(
                DEFAULT_DLQ_TOPIC, DEFAULT_DLQ_PARTITIONS, (short) 1, DEFAULT_DLQ_RETENTION, null);
    }

    public record TopicConfig(
            String name,
            int partitions,
            short replicationFactor,
            Duration retention,
            Long segmentBytes) {

        public TopicConfig {
            name = (name != null && !name.isBlank()) ? name : "default.topic";
            partitions = partitions > 0 ? partitions : 1;
            replicationFactor = replicationFactor > 0 ? replicationFactor : (short) 1;
            retention = retention != null ? retention : Duration.ofHours(1);
        }
    }
}
