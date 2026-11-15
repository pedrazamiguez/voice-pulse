package com.voicepulse.routing.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.instancio.Select.field;

import com.voicepulse.shared.model.CallId;
import java.time.Instant;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import org.instancio.Instancio;
import org.instancio.Model;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("OperatorPresence Domain Record Tests")
class OperatorPresenceTest {

    private static final Instant NOW = Instant.parse("2026-11-14T10:00:00Z");

    private static final Model<OperatorPresence> AVAILABLE_PRESENCE_MODEL =
            Instancio.of(OperatorPresence.class)
                    .set(field(OperatorPresence::operatorId), OperatorId.fromString("op-01"))
                    .set(field(OperatorPresence::displayName), "Elena Navarro")
                    .set(field(OperatorPresence::status), OperatorAvailabilityStatus.AVAILABLE)
                    .set(
                            field(OperatorPresence::skills),
                            Set.of(
                                    SkillGroup.DISRUPTION_DESK_GOLD,
                                    SkillGroup.DISRUPTION_DESK_PRIORITY))
                    .set(field(OperatorPresence::activeCallId), Optional.empty())
                    .set(field(OperatorPresence::lastHeartbeatAt), NOW)
                    .toModel();

    @Test
    @DisplayName("Should enforce defensive copy of skills and validate invariants")
    void shouldEnforceDefensiveSkillsCopy() {
        Set<SkillGroup> mutableSkills = new HashSet<>();
        mutableSkills.add(SkillGroup.DISRUPTION_DESK_GOLD);

        OperatorPresence presence =
                Instancio.of(AVAILABLE_PRESENCE_MODEL)
                        .set(field(OperatorPresence::skills), mutableSkills)
                        .create();

        mutableSkills.add(SkillGroup.DISRUPTION_DESK_STANDARD);

        assertThat(presence.skills()).containsExactly(SkillGroup.DISRUPTION_DESK_GOLD);
        assertThat(presence.status().canAcceptCall()).isTrue();
        assertThatThrownBy(() -> presence.skills().add(SkillGroup.DISRUPTION_DESK_PRIORITY))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("Should reject AVAILABLE operator holding an activeCallId")
    void shouldRejectAvailableWithActiveCall() {
        CallId callId = CallId.generate();
        assertThatThrownBy(
                        () ->
                                new OperatorPresence(
                                        OperatorId.fromString("op-01"),
                                        "Elena",
                                        OperatorAvailabilityStatus.AVAILABLE,
                                        Set.of(SkillGroup.DISRUPTION_DESK_STANDARD),
                                        Optional.of(callId),
                                        NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("AVAILABLE operator cannot hold an activeCallId");
    }

    @Test
    @DisplayName("Should reject empty skills set and blank displayName")
    void shouldRejectInvalidSkillsAndDisplayName() {
        assertThatThrownBy(
                        () ->
                                new OperatorPresence(
                                        OperatorId.fromString("op-01"),
                                        "   ",
                                        OperatorAvailabilityStatus.AVAILABLE,
                                        Set.of(SkillGroup.DISRUPTION_DESK_STANDARD),
                                        Optional.empty(),
                                        NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("displayName cannot be blank");

        assertThatThrownBy(
                        () ->
                                new OperatorPresence(
                                        OperatorId.fromString("op-01"),
                                        "Elena",
                                        OperatorAvailabilityStatus.AVAILABLE,
                                        Set.of(),
                                        Optional.empty(),
                                        NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("skills must contain at least one SkillGroup");
    }

    @Test
    @DisplayName(
            "Should transition cleanly across assignCall, releaseCall, withStatus, and heartbeat")
    void shouldTransitionPresenceStatesCleanly() {
        OperatorPresence available = Instancio.create(AVAILABLE_PRESENCE_MODEL);
        CallId callId = CallId.generate();
        Instant assignedAt = NOW.plusSeconds(10);

        OperatorPresence busy = available.assignCall(callId, assignedAt);
        assertThat(busy.status()).isEqualTo(OperatorAvailabilityStatus.BUSY);
        assertThat(busy.status().canAcceptCall()).isFalse();
        assertThat(busy.activeCallId()).contains(callId);
        assertThat(busy.lastHeartbeatAt()).isEqualTo(assignedAt);

        Instant pausedAt = assignedAt.plusSeconds(5);
        OperatorPresence pausedWhileBusy =
                busy.withStatus(OperatorAvailabilityStatus.PAUSED, pausedAt);
        assertThat(pausedWhileBusy.status()).isEqualTo(OperatorAvailabilityStatus.PAUSED);
        assertThat(pausedWhileBusy.activeCallId()).contains(callId);

        Instant releasedAt = pausedAt.plusSeconds(30);
        OperatorPresence released =
                busy.releaseCall(OperatorAvailabilityStatus.AVAILABLE, releasedAt);
        assertThat(released.status()).isEqualTo(OperatorAvailabilityStatus.AVAILABLE);
        assertThat(released.activeCallId()).isEmpty();

        Instant heartbeatAt = releasedAt.plusSeconds(15);
        OperatorPresence refreshed = released.refreshHeartbeat(heartbeatAt);
        assertThat(refreshed.lastHeartbeatAt()).isEqualTo(heartbeatAt);
    }
}
