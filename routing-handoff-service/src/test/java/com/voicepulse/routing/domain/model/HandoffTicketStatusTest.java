package com.voicepulse.routing.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.voicepulse.shared.model.CallId;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("HandoffTicketStatus Domain Enum Tests")
class HandoffTicketStatusTest {

    private static final Instant NOW = Instant.parse("2026-11-15T00:00:00Z");

    @Test
    @DisplayName("Should map all sealed HandoffLifecycleState variants exhaustively")
    void shouldMapAllLifecycleStates() {
        CallId callId = CallId.generate();
        UUID ticketId = UUID.randomUUID();
        OperatorId operatorId = OperatorId.fromString("op-enum-1");

        assertThat(
                        HandoffTicketStatus.fromLifecycleState(
                                new HandoffLifecycleState.Queued(
                                        callId, ticketId, SkillGroup.DISRUPTION_DESK_GOLD, 0, NOW)))
                .isEqualTo(HandoffTicketStatus.QUEUED);
        assertThat(
                        HandoffTicketStatus.fromLifecycleState(
                                new HandoffLifecycleState.Assigned(
                                        callId,
                                        ticketId,
                                        operatorId,
                                        SkillGroup.DISRUPTION_DESK_GOLD,
                                        NOW)))
                .isEqualTo(HandoffTicketStatus.ASSIGNED);
        assertThat(
                        HandoffTicketStatus.fromLifecycleState(
                                new HandoffLifecycleState.AudioBridged(
                                        callId,
                                        ticketId,
                                        operatorId,
                                        "/ws/operators/op-enum-1/calls/" + callId + "/audio",
                                        NOW)))
                .isEqualTo(HandoffTicketStatus.AUDIO_BRIDGED);
        assertThat(
                        HandoffTicketStatus.fromLifecycleState(
                                new HandoffLifecycleState.Completed(
                                        callId, ticketId, operatorId, "Resolved", NOW)))
                .isEqualTo(HandoffTicketStatus.COMPLETED);
        assertThat(
                        HandoffTicketStatus.fromLifecycleState(
                                new HandoffLifecycleState.Abandoned(
                                        callId,
                                        ticketId,
                                        CallAbandonReason.CALLER_DISCONNECTED.name(),
                                        NOW)))
                .isEqualTo(HandoffTicketStatus.ABANDONED);
    }

    @Test
    @DisplayName("Should reject null HandoffLifecycleState and normalize blank abandon reasons")
    void shouldValidateNullLifecycleStateAndNormalizeReason() {
        assertThatThrownBy(() -> HandoffTicketStatus.fromLifecycleState(null))
                .isInstanceOf(NullPointerException.class);
        assertThat(CallAbandonReason.normalizeOrDefault(null)).isEqualTo("CALLER_DISCONNECTED");
        assertThat(CallAbandonReason.normalizeOrDefault("   ")).isEqualTo("CALLER_DISCONNECTED");
        assertThat(CallAbandonReason.normalizeOrDefault("  DROPPED_AUDIO  "))
                .isEqualTo("DROPPED_AUDIO");
    }
}
