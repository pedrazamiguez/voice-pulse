package com.voicepulse.gateway.infrastructure.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuration properties mapped to {@code voicepulse.gateway.kafka}. */
@ConfigurationProperties(prefix = "voicepulse.gateway.kafka")
public record GatewayKafkaProperties(
        String topic, Duration sendTimeout, long bufferMemory, Duration maxBlockTimeout) {

    public static final String DEFAULT_TOPIC = "telephony.calls.raw";
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(3);
    public static final long DEFAULT_BUFFER_MEMORY = 32L * 1024L * 1024L; // 32 MB
    public static final Duration DEFAULT_MAX_BLOCK_TIMEOUT = Duration.ofSeconds(3);

    public GatewayKafkaProperties {
        topic = (topic != null && !topic.isBlank()) ? topic : DEFAULT_TOPIC;
        sendTimeout = (sendTimeout != null) ? sendTimeout : DEFAULT_TIMEOUT;
        bufferMemory = (bufferMemory > 0) ? bufferMemory : DEFAULT_BUFFER_MEMORY;
        maxBlockTimeout = (maxBlockTimeout != null) ? maxBlockTimeout : DEFAULT_MAX_BLOCK_TIMEOUT;
    }
}
