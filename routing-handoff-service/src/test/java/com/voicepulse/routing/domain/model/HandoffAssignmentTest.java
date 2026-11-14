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

@DisplayName("HandoffAssignment Domain Record Tests")
class HandoffAssignmentTest {

    private static final Instant NOW = Instant.parse("2026-11-14T10:00:00Z");

    @Test
    @DisplayName("Should construct valid HandoffAssignment and trim bridgeEndpoint")
    void shouldConstructValidHandoffAssignment() {
        CallId callId = CallId.generate();
        UUID ticketId = UUID.randomUUID();
        OperatorId operatorId = OperatorId.fromString("op-12");
        String endpoint = "/ws/operators/op-12/calls/" + callId + "/audio";

        HandoffAssignment assignment =
                Instancio.of(HandoffAssignment.class)
                        .set(field(HandoffAssignment::callId), callId)
                        .set(field(HandoffAssignment::ticketId), ticketId)
                        .set(field(HandoffAssignment::operatorId), operatorId)
                        .set(
                                field(HandoffAssignment::skillGroup),
                                SkillGroup.DISRUPTION_DESK_PRIORITY)
                        .set(field(HandoffAssignment::bridgeEndpoint), "  " + endpoint + "  ")
                        .set(field(HandoffAssignment::assignedAt), NOW)
                        .create();

        assertThat(assignment.callId()).isEqualTo(callId);
        assertThat(assignment.ticketId()).isEqualTo(ticketId);
        assertThat(assignment.operatorId()).isEqualTo(operatorId);
        assertThat(assignment.skillGroup()).isEqualTo(SkillGroup.DISRUPTION_DESK_PRIORITY);
        assertThat(assignment.bridgeEndpoint()).isEqualTo(endpoint);
        assertThat(assignment.assignedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("Should reject null or blank bridgeEndpoint and null required fields")
    void shouldRejectInvalidHandoffAssignment() {
        CallId callId = CallId.generate();
        UUID ticketId = UUID.randomUUID();
        OperatorId operatorId = OperatorId.fromString("op-12");

        assertThatThrownBy(
                        () ->
                                new HandoffAssignment(
                                        null,
                                        ticketId,
                                        operatorId,
                                        SkillGroup.DISRUPTION_DESK_STANDARD,
                                        "/ws/op",
                                        NOW))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("callId cannot be null");

        assertThatThrownBy(
                        () ->
                                new HandoffAssignment(
                                        callId,
                                        ticketId,
                                        operatorId,
                                        SkillGroup.DISRUPTION_DESK_STANDARD,
                                        null,
                                        NOW))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("bridgeEndpoint cannot be null");

        assertThatThrownBy(
                        () ->
                                new HandoffAssignment(
                                        callId,
                                        ticketId,
                                        operatorId,
                                        SkillGroup.DISRUPTION_DESK_STANDARD,
                                        "   ",
                                        NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bridgeEndpoint cannot be blank");
    }
}
