package com.voicepulse.routing.infrastructure.ws.dto;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Sealed WebSocket signaling envelope pushed from {@code routing-handoff-service} to connected
 * Operator Web Console sessions over {@code /ws/operators/{operatorId}}.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(
            value = OperatorWsMessage.ConsoleSnapshotMessage.class,
            name = "CONSOLE_SNAPSHOT"),
    @JsonSubTypes.Type(
            value = OperatorWsMessage.AssignmentPushedMessage.class,
            name = "ASSIGNMENT"),
    @JsonSubTypes.Type(value = OperatorWsMessage.QueueMetricsMessage.class, name = "QUEUE_METRICS"),
    @JsonSubTypes.Type(
            value = OperatorWsMessage.HandoffStateChangedMessage.class,
            name = "HANDOFF_STATE"),
    @JsonSubTypes.Type(value = OperatorWsMessage.PongMessage.class, name = "PONG"),
    @JsonSubTypes.Type(value = OperatorWsMessage.ConsoleErrorMessage.class, name = "ERROR")
})
public sealed interface OperatorWsMessage
        permits OperatorWsMessage.ConsoleSnapshotMessage,
                OperatorWsMessage.AssignmentPushedMessage,
                OperatorWsMessage.QueueMetricsMessage,
                OperatorWsMessage.HandoffStateChangedMessage,
                OperatorWsMessage.PongMessage,
                OperatorWsMessage.ConsoleErrorMessage {

    /** Full initial or replayed operator console state snapshot. */
    record ConsoleSnapshotMessage(
            OperatorPresenceDto presence,
            OperatorAssignmentPayloadDto activeAssignment,
            QueueOverviewDto queueOverview,
            Instant generatedAt)
            implements OperatorWsMessage {

        public ConsoleSnapshotMessage {
            Objects.requireNonNull(queueOverview, "queueOverview cannot be null");
            Objects.requireNonNull(generatedAt, "generatedAt cannot be null");
        }
    }

    /** Real-time assignment push containing pre-populated disruption ticket and transcript. */
    record AssignmentPushedMessage(OperatorAssignmentPayloadDto assignment, Instant dispatchedAt)
            implements OperatorWsMessage {

        public AssignmentPushedMessage {
            Objects.requireNonNull(assignment, "assignment cannot be null");
            Objects.requireNonNull(dispatchedAt, "dispatchedAt cannot be null");
        }
    }

    /** Real-time skill-group queue depth broadcast. */
    record QueueMetricsMessage(
            String skillGroup,
            long queueDepth,
            Map<String, Long> depthsBySkillGroup,
            Instant updatedAt)
            implements OperatorWsMessage {

        public QueueMetricsMessage {
            Objects.requireNonNull(skillGroup, "skillGroup cannot be null");
            Objects.requireNonNull(updatedAt, "updatedAt cannot be null");
            depthsBySkillGroup =
                    depthsBySkillGroup == null ? Map.of() : Map.copyOf(depthsBySkillGroup);
        }

        @Override
        public Map<String, Long> depthsBySkillGroup() {
            return depthsBySkillGroup == null ? Map.of() : Map.copyOf(depthsBySkillGroup);
        }
    }

    /** Handoff lifecycle state change notification. */
    record HandoffStateChangedMessage(HandoffStateDto state, Instant changedAt)
            implements OperatorWsMessage {

        public HandoffStateChangedMessage {
            Objects.requireNonNull(state, "state cannot be null");
            Objects.requireNonNull(changedAt, "changedAt cannot be null");
        }
    }

    /** Heartbeat/ping acknowledgement frame. */
    record PongMessage(String operatorId, Instant serverTime) implements OperatorWsMessage {

        public PongMessage {
            Objects.requireNonNull(operatorId, "operatorId cannot be null");
            Objects.requireNonNull(serverTime, "serverTime cannot be null");
        }
    }

    /** Protocol or validation error frame sent to the operator console client. */
    record ConsoleErrorMessage(String code, String message, Instant timestamp)
            implements OperatorWsMessage {

        public ConsoleErrorMessage {
            Objects.requireNonNull(code, "code cannot be null");
            Objects.requireNonNull(message, "message cannot be null");
            Objects.requireNonNull(timestamp, "timestamp cannot be null");
        }
    }
}
