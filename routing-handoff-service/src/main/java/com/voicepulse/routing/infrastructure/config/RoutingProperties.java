package com.voicepulse.routing.infrastructure.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Fail-fast configuration properties bound to {@code voicepulse.routing}.
 *
 * <p>Default values are supplied exclusively via {@code ${ENV_VAR:fallback}} expressions in {@code
 * application.yml}. Invalid or missing values fail application startup immediately.
 */
@ConfigurationProperties(prefix = "voicepulse.routing")
public record RoutingProperties(
        String ticketIntentTopic,
        String callControlTopic,
        String consumerGroup,
        Duration operatorHeartbeatTtl,
        String operatorBridgeBasePath,
        int maxQueueCapacity) {

    public RoutingProperties {
        ticketIntentTopic = requireNonBlank(ticketIntentTopic, "ticketIntentTopic");
        callControlTopic = requireNonBlank(callControlTopic, "callControlTopic");
        consumerGroup = requireNonBlank(consumerGroup, "consumerGroup");
        validatePositiveDuration(operatorHeartbeatTtl, "operatorHeartbeatTtl");
        operatorBridgeBasePath = normalizeBridgeBasePath(operatorBridgeBasePath);
        if (maxQueueCapacity <= 0) {
            throw new IllegalArgumentException(
                    "maxQueueCapacity must be positive, but was: " + maxQueueCapacity);
        }
    }

    private static String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be null or blank");
        }
        return value.trim();
    }

    private static void validatePositiveDuration(Duration duration, String fieldName) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(
                    fieldName + " must be positive, but was: " + duration);
        }
    }

    private static String normalizeBridgeBasePath(String path) {
        String trimmed = requireNonBlank(path, "operatorBridgeBasePath");
        if (!trimmed.startsWith("/")) {
            throw new IllegalArgumentException(
                    "operatorBridgeBasePath must start with '/': " + trimmed);
        }
        if (trimmed.length() > 1 && trimmed.endsWith("/")) {
            return trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }
}
