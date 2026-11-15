package com.voicepulse.routing.infrastructure.redis.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.voicepulse.routing.infrastructure.redis.model.OperatorPresenceHash;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("OperatorPresenceHashMapper Unit Tests")
class OperatorPresenceHashMapperTest {

    private static final Instant NOW = Instant.parse("2026-11-15T10:00:00Z");

    private final OperatorPresenceHashMapper mapper = new OperatorPresenceHashMapper();

    @Test
    @DisplayName("Should round-trip flat Redis hash without emitting Spring @class metadata")
    void shouldRoundTripFlatHashWithoutTypeMetadata() {
        OperatorPresenceHash busyHash =
                new OperatorPresenceHash(
                        "op-1",
                        "Elena Navarro",
                        "BUSY",
                        "DISRUPTION_DESK_GOLD,DISRUPTION_DESK_PRIORITY",
                        "call-999",
                        NOW.toString());

        Map<String, String> encoded = mapper.toHash(busyHash);
        assertThat(encoded)
                .doesNotContainKeys("@class", "_class")
                .containsEntry("operatorId", "op-1")
                .containsEntry("displayName", "Elena Navarro")
                .containsEntry("status", "BUSY")
                .containsEntry("skills", "DISRUPTION_DESK_GOLD,DISRUPTION_DESK_PRIORITY")
                .containsEntry("activeCallId", "call-999")
                .containsEntry("lastHeartbeatAt", NOW.toString());

        assertThat(mapper.fromHash(encoded)).isEqualTo(busyHash);
        assertThat(mapper.tryFromHash(encoded)).contains(busyHash);
    }

    @Test
    @DisplayName("Should preserve blank activeCallId normalization during hash round-trip")
    void shouldNormalizeBlankActiveCallId() {
        OperatorPresenceHash availableHash =
                new OperatorPresenceHash(
                        "op-2",
                        "Carlos Diaz",
                        "AVAILABLE",
                        "DISRUPTION_DESK_STANDARD",
                        null,
                        NOW.toString());

        Map<String, String> encoded = mapper.toHash(availableHash);
        assertThat(encoded).doesNotContainKeys("@class", "_class");
        assertThat(mapper.tryFromHash(encoded)).contains(availableHash);
    }

    @Test
    @DisplayName("Should return Optional.empty when hash entries are absent, partial, or blank")
    void shouldReturnEmptyForMissingOrInvalidHashes() {
        assertThat(mapper.tryFromHash(null)).isEmpty();
        assertThat(mapper.tryFromHash(Map.of())).isEmpty();
        assertThat(mapper.tryFromHash(Map.of("operatorId", "op-partial"))).isEmpty();
        assertThat(
                        mapper.tryFromHash(
                                Map.of(
                                        "operatorId", "op-blank",
                                        "displayName", "   ",
                                        "status", "AVAILABLE",
                                        "skills", "DISRUPTION_DESK_GOLD",
                                        "activeCallId", "",
                                        "lastHeartbeatAt", NOW.toString())))
                .isEmpty();
    }

    @Test
    @DisplayName("Should fail fast on strict fromHash and null toHash calls")
    void shouldFailFastOnInvalidStrictCalls() {
        assertThatThrownBy(() -> mapper.toHash(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> mapper.fromHash(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> mapper.fromHash(Map.of()))
                .isInstanceOf(IllegalArgumentException.class);

        Optional<OperatorPresenceHash> result =
                mapper.tryFromHash(
                        Map.of(
                                "operatorId", "op-3",
                                "displayName", "Laura Gomez",
                                "status", "PAUSED",
                                "skills", "DISRUPTION_DESK_ACCESSIBILITY",
                                "lastHeartbeatAt", NOW.toString()));
        assertThat(result).isPresent();
        assertThat(result.get().activeCallId()).isEmpty();
    }
}
