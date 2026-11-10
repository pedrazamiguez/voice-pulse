package com.voicepulse.gateway.infrastructure.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Configuration properties mapped to {@code voicepulse.gateway.kafka}. */
@ConfigurationProperties(prefix = "voicepulse.gateway.kafka")
public record GatewayKafkaProperties(
        @DefaultValue("telephony.calls.raw") String topic,
        @DefaultValue("telephony.calls.control") String controlTopic,
        @DefaultValue("edge-gateway-control-workers") String consumerGroup,
        @DefaultValue("3s") Duration sendTimeout,
        @DefaultValue("33554432") long bufferMemory,
        @DefaultValue("3s") Duration maxBlockTimeout) {

    public GatewayKafkaProperties {
        if (topic == null || topic.isBlank()) {
            throw new IllegalArgumentException("topic must not be blank");
        }
        if (controlTopic == null || controlTopic.isBlank()) {
            throw new IllegalArgumentException("controlTopic must not be blank");
        }
        if (consumerGroup == null || consumerGroup.isBlank()) {
            throw new IllegalArgumentException("consumerGroup must not be blank");
        }
        if (sendTimeout == null || sendTimeout.isNegative() || sendTimeout.isZero()) {
            throw new IllegalArgumentException("sendTimeout must be positive: " + sendTimeout);
        }
        if (bufferMemory <= 0) {
            throw new IllegalArgumentException("bufferMemory must be positive: " + bufferMemory);
        }
        if (maxBlockTimeout == null || maxBlockTimeout.isNegative() || maxBlockTimeout.isZero()) {
            throw new IllegalArgumentException(
                    "maxBlockTimeout must be positive: " + maxBlockTimeout);
        }
    }
}
