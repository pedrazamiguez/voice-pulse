package com.voicepulse.routing.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.instancio.Select.field;

import com.voicepulse.shared.model.CallId;
import java.time.Instant;
import java.util.UUID;
import org.instancio.Instancio;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("HandoffLifecycleState Sealed Hierarchy Domain Tests")
class HandoffLifecycleStateTest {

    private static final CallId CALL_ID = CallId.generate();
    private static final UUID TICKET_ID = UUID.randomUUID();
    private static final OperatorId OPERATOR_ID = OperatorId.fromString("op-gold-1");
    private static final Instant NOW = Instant.parse("2026-11-14T10:00:00Z");

    @Test
    @DisplayName("Should perform exhaustive pattern matching across all 5 lifecycle states")
    void shouldPerformExhaustivePatternMatching() {
        HandoffLifecycleState queued =
                Instancio.of(HandoffLifecycleState.Queued.class)
                        .set(field(HandoffLifecycleState.Queued::callId), CALL_ID)
                        .set(field(HandoffLifecycleState.Queued::ticketId), TICKET_ID)
                        .set(
                                field(HandoffLifecycleState.Queued::skillGroup),
                                SkillGroup.DISRUPTION_DESK_GOLD)
                        .set(field(HandoffLifecycleState.Queued::queuePosition), 2)
                        .set(field(HandoffLifecycleState.Queued::queuedAt), NOW)
                        .create();

        HandoffLifecycleState assigned =
                Instancio.of(HandoffLifecycleState.Assigned.class)
                        .set(field(HandoffLifecycleState.Assigned::callId), CALL_ID)
                        .set(field(HandoffLifecycleState.Assigned::ticketId), TICKET_ID)
                        .set(field(HandoffLifecycleState.Assigned::operatorId), OPERATOR_ID)
                        .set(
                                field(HandoffLifecycleState.Assigned::skillGroup),
                                SkillGroup.DISRUPTION_DESK_GOLD)
                        .set(field(HandoffLifecycleState.Assigned::assignedAt), NOW)
                        .create();

        HandoffLifecycleState bridged =
                Instancio.of(HandoffLifecycleState.AudioBridged.class)
                        .set(field(HandoffLifecycleState.AudioBridged::callId), CALL_ID)
                        .set(field(HandoffLifecycleState.AudioBridged::ticketId), TICKET_ID)
                        .set(field(HandoffLifecycleState.AudioBridged::operatorId), OPERATOR_ID)
                        .set(
                                field(HandoffLifecycleState.AudioBridged::bridgeEndpoint),
                                "/ws/operators/op-gold-1/calls/" + CALL_ID + "/audio")
                        .set(field(HandoffLifecycleState.AudioBridged::bridgedAt), NOW)
                        .create();

        HandoffLifecycleState completed =
                Instancio.of(HandoffLifecycleState.Completed.class)
                        .set(field(HandoffLifecycleState.Completed::callId), CALL_ID)
                        .set(field(HandoffLifecycleState.Completed::ticketId), TICKET_ID)
                        .set(field(HandoffLifecycleState.Completed::operatorId), OPERATOR_ID)
                        .set(
                                field(HandoffLifecycleState.Completed::resolutionNotes),
                                "Rebooked on SW-208")
                        .set(field(HandoffLifecycleState.Completed::completedAt), NOW)
                        .create();

        HandoffLifecycleState abandoned =
                Instancio.of(HandoffLifecycleState.Abandoned.class)
                        .set(field(HandoffLifecycleState.Abandoned::callId), CALL_ID)
                        .set(field(HandoffLifecycleState.Abandoned::ticketId), TICKET_ID)
                        .set(field(HandoffLifecycleState.Abandoned::reason), "CALLER_HANGUP")
                        .set(field(HandoffLifecycleState.Abandoned::abandonedAt), NOW)
                        .create();

        assertThat(describe(queued)).isEqualTo("QUEUED:2");
        assertThat(describe(assigned)).isEqualTo("ASSIGNED:op-gold-1");
        assertThat(describe(bridged)).startsWith("BRIDGED:/ws/operators/op-gold-1");
        assertThat(describe(completed)).isEqualTo("COMPLETED:Rebooked on SW-208");
        assertThat(describe(abandoned)).isEqualTo("ABANDONED:CALLER_HANGUP");
    }

    @Test
    @DisplayName("Should enforce invariant checks in state compact constructors")
    void shouldEnforceStateInvariants() {
        assertThatThrownBy(
                        () ->
                                new HandoffLifecycleState.Queued(
                                        CALL_ID,
                                        TICKET_ID,
                                        SkillGroup.DISRUPTION_DESK_STANDARD,
                                        -1,
                                        NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("queuePosition cannot be negative");

        assertThatThrownBy(
                        () ->
                                new HandoffLifecycleState.AudioBridged(
                                        CALL_ID, TICKET_ID, OPERATOR_ID, "   ", NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bridgeEndpoint cannot be blank");

        assertThatThrownBy(
                        () -> new HandoffLifecycleState.Abandoned(CALL_ID, TICKET_ID, "   ", NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reason cannot be blank");
    }

    private String describe(HandoffLifecycleState state) {
        return switch (state) {
            case HandoffLifecycleState.Queued q -> "QUEUED:" + q.queuePosition();
            case HandoffLifecycleState.Assigned a -> "ASSIGNED:" + a.operatorId();
            case HandoffLifecycleState.AudioBridged b -> "BRIDGED:" + b.bridgeEndpoint();
            case HandoffLifecycleState.Completed c -> "COMPLETED:" + c.resolutionNotes();
            case HandoffLifecycleState.Abandoned ab -> "ABANDONED:" + ab.reason();
        };
    }
}
