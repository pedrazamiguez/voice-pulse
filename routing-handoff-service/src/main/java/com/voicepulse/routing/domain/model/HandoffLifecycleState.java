package com.voicepulse.routing.domain.model;

import com.voicepulse.shared.model.CallId;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Sealed interface modeling the bounded operator handoff lifecycle state machine (RFC-0001 §4.5,
 * RFC-0003 §4).
 */
public sealed interface HandoffLifecycleState
        permits HandoffLifecycleState.Queued,
                HandoffLifecycleState.Assigned,
                HandoffLifecycleState.AudioBridged,
                HandoffLifecycleState.Completed,
                HandoffLifecycleState.Abandoned {

    /** Call session identifier associated with this lifecycle state. */
    CallId callId();

    /** Ticket queued in a prioritized skill-group queue awaiting an available operator. */
    record Queued(
            CallId callId,
            UUID ticketId,
            SkillGroup skillGroup,
            int queuePosition,
            Instant queuedAt)
            implements HandoffLifecycleState {

        public Queued {
            Objects.requireNonNull(callId, "callId cannot be null");
            Objects.requireNonNull(ticketId, "ticketId cannot be null");
            Objects.requireNonNull(skillGroup, "skillGroup cannot be null");
            Objects.requireNonNull(queuedAt, "queuedAt cannot be null");
            if (queuePosition < 0) {
                throw new IllegalArgumentException(
                        "queuePosition cannot be negative: " + queuePosition);
            }
        }
    }

    /** Call claimed by or automatically matched to an available human operator. */
    record Assigned(
            CallId callId,
            UUID ticketId,
            OperatorId operatorId,
            SkillGroup skillGroup,
            Instant assignedAt)
            implements HandoffLifecycleState {

        public Assigned {
            Objects.requireNonNull(callId, "callId cannot be null");
            Objects.requireNonNull(ticketId, "ticketId cannot be null");
            Objects.requireNonNull(operatorId, "operatorId cannot be null");
            Objects.requireNonNull(skillGroup, "skillGroup cannot be null");
            Objects.requireNonNull(assignedAt, "assignedAt cannot be null");
        }
    }

    /** Caller audio stream actively bridged to the assigned operator audio WebSocket. */
    record AudioBridged(
            CallId callId,
            UUID ticketId,
            OperatorId operatorId,
            String bridgeEndpoint,
            Instant bridgedAt)
            implements HandoffLifecycleState {

        public AudioBridged {
            Objects.requireNonNull(callId, "callId cannot be null");
            Objects.requireNonNull(ticketId, "ticketId cannot be null");
            Objects.requireNonNull(operatorId, "operatorId cannot be null");
            Objects.requireNonNull(bridgeEndpoint, "bridgeEndpoint cannot be null");
            Objects.requireNonNull(bridgedAt, "bridgedAt cannot be null");
            if (bridgeEndpoint.isBlank()) {
                throw new IllegalArgumentException("bridgeEndpoint cannot be blank");
            }
        }
    }

    /** Operator handoff completed and ticket resolved or closed. */
    record Completed(
            CallId callId,
            UUID ticketId,
            OperatorId operatorId,
            String resolutionNotes,
            Instant completedAt)
            implements HandoffLifecycleState {

        public Completed {
            Objects.requireNonNull(callId, "callId cannot be null");
            Objects.requireNonNull(ticketId, "ticketId cannot be null");
            Objects.requireNonNull(operatorId, "operatorId cannot be null");
            Objects.requireNonNull(resolutionNotes, "resolutionNotes cannot be null");
            Objects.requireNonNull(completedAt, "completedAt cannot be null");
        }
    }

    /** Call disconnected or abandoned before operator handoff completion. */
    record Abandoned(CallId callId, UUID ticketId, String reason, Instant abandonedAt)
            implements HandoffLifecycleState {

        public Abandoned {
            Objects.requireNonNull(callId, "callId cannot be null");
            Objects.requireNonNull(ticketId, "ticketId cannot be null");
            Objects.requireNonNull(reason, "reason cannot be null");
            Objects.requireNonNull(abandonedAt, "abandonedAt cannot be null");
            if (reason.isBlank()) {
                throw new IllegalArgumentException("reason cannot be blank");
            }
        }
    }
}
