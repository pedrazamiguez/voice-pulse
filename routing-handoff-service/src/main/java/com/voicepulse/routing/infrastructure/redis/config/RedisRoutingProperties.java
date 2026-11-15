package com.voicepulse.routing.infrastructure.redis.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Fail-fast Redis configuration properties bound to {@code voicepulse.routing.redis}.
 *
 * <p>Default values are supplied exclusively via {@code ${ENV_VAR:fallback}} expressions in {@code
 * application.yml}. Invalid or missing values fail application startup immediately.
 */
@ConfigurationProperties(prefix = "voicepulse.routing.redis")
public record RedisRoutingProperties(boolean enabled, String keyPrefix, Duration operationTimeout) {

    public RedisRoutingProperties {
        keyPrefix = normalizeKeyPrefix(keyPrefix);
        validatePositiveDuration(operationTimeout, "operationTimeout");
    }

    private static String normalizeKeyPrefix(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            throw new IllegalArgumentException("keyPrefix must not be null or blank");
        }
        String trimmed = prefix.trim();
        if (trimmed.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("keyPrefix must not contain whitespace: " + trimmed);
        }
        while (trimmed.endsWith(":")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        if (trimmed.isBlank()) {
            throw new IllegalArgumentException("keyPrefix must not be blank");
        }
        return trimmed;
    }

    private static void validatePositiveDuration(Duration duration, String fieldName) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(
                    fieldName + " must be positive, but was: " + duration);
        }
    }
}
