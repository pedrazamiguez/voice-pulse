package com.voicepulse.routing.infrastructure.kafka.config;

import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Fail-fast Kafka adapter configuration properties bound to {@code voicepulse.routing.kafka}.
 *
 * <p>Default values are supplied exclusively via {@code ${ENV_VAR:fallback}} expressions in {@code
 * application.yml}. Invalid or missing values fail application startup immediately.
 */
@ConfigurationProperties(prefix = "voicepulse.routing.kafka")
public record RoutingKafkaProperties(
        boolean enabled,
        int concurrency,
        String autoOffsetReset,
        Duration sendTimeout,
        String dlqTopic) {

    private static final Set<String> VALID_OFFSET_RESET_POLICIES =
            Set.of("earliest", "latest", "none");

    public RoutingKafkaProperties {
        if (concurrency <= 0) {
            throw new IllegalArgumentException(
                    "concurrency must be positive, but was: " + concurrency);
        }
        autoOffsetReset = normalizeAutoOffsetReset(autoOffsetReset);
        validatePositiveDuration(sendTimeout, "sendTimeout");
        dlqTopic = requireNonBlank(dlqTopic, "dlqTopic");
    }

    private static String normalizeAutoOffsetReset(String value) {
        String normalized = requireNonBlank(value, "autoOffsetReset").toLowerCase(Locale.ROOT);
        if (!VALID_OFFSET_RESET_POLICIES.contains(normalized)) {
            throw new IllegalArgumentException(
                    "autoOffsetReset must be one of earliest, latest, or none, but was: " + value);
        }
        return normalized;
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
}
