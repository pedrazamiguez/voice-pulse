package com.voicepulse.gateway.infrastructure.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuration properties mapped to {@code voicepulse.gateway.kafka}. */
@ConfigurationProperties(prefix = "voicepulse.gateway.kafka")
public record GatewayKafkaProperties(
        String topic, Duration sendTimeout, long bufferMemory, Duration maxBlockTimeout) {}
