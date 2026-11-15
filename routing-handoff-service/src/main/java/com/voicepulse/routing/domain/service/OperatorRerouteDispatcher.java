package com.voicepulse.routing.domain.service;

import com.voicepulse.routing.domain.model.HandoffAssignment;
import com.voicepulse.routing.domain.model.HandoffLifecycleState;
import com.voicepulse.routing.domain.model.OperatorId;
import com.voicepulse.routing.domain.model.SkillGroup;
import com.voicepulse.routing.domain.ports.outbound.AudioRerouteSignalPort;
import com.voicepulse.routing.domain.ports.outbound.OperatorDispatchPort;
import com.voicepulse.shared.event.OperatorRerouteSignal;
import com.voicepulse.shared.event.TicketFormFilledEvent;
import com.voicepulse.shared.model.CallId;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Pure domain dispatcher constructing operator audio bridge endpoints, emitting monotonic {@link
 * OperatorRerouteSignal} events, and notifying connected operator consoles of handoff and queue
 * changes.
 */
public class OperatorRerouteDispatcher {

    private final OperatorDispatchPort operatorDispatchPort;
    private final AudioRerouteSignalPort audioRerouteSignalPort;
    private final String operatorBridgeBasePath;
    private final AtomicLong rerouteSequence = new AtomicLong(1L);

    public OperatorRerouteDispatcher(
            OperatorDispatchPort operatorDispatchPort,
            AudioRerouteSignalPort audioRerouteSignalPort,
            String operatorBridgeBasePath) {
        this.operatorDispatchPort =
                Objects.requireNonNull(operatorDispatchPort, "operatorDispatchPort cannot be null");
        this.audioRerouteSignalPort =
                Objects.requireNonNull(
                        audioRerouteSignalPort, "audioRerouteSignalPort cannot be null");
        Objects.requireNonNull(operatorBridgeBasePath, "operatorBridgeBasePath cannot be null");
        String trimmedPath = operatorBridgeBasePath.trim();
        if (trimmedPath.isBlank() || !trimmedPath.startsWith("/")) {
            throw new IllegalArgumentException(
                    "operatorBridgeBasePath must be non-blank and start with '/': "
                            + operatorBridgeBasePath);
        }
        this.operatorBridgeBasePath =
                trimmedPath.length() > 1 && trimmedPath.endsWith("/")
                        ? trimmedPath.substring(0, trimmedPath.length() - 1)
                        : trimmedPath;
    }

    /** Creates a {@link HandoffAssignment} with the canonical operator audio bridge endpoint. */
    public HandoffAssignment createAssignment(
            CallId callId,
            UUID ticketId,
            OperatorId operatorId,
            SkillGroup skillGroup,
            Instant assignedAt) {
        String bridgeEndpoint =
                operatorBridgeBasePath + "/" + operatorId.value() + "/calls/" + callId + "/audio";
        return new HandoffAssignment(
                callId, ticketId, operatorId, skillGroup, bridgeEndpoint, assignedAt);
    }

    /** Pushes console assignment/state updates and emits the caller audio reroute signal. */
    public void dispatchAssignment(
            HandoffAssignment assignment,
            TicketFormFilledEvent ticketEvent,
            HandoffLifecycleState.Assigned assignedState,
            Instant timestamp) {
        operatorDispatchPort.pushAssignment(assignment, ticketEvent);
        operatorDispatchPort.notifyHandoffStateChanged(assignedState);
        OperatorRerouteSignal rerouteSignal =
                new OperatorRerouteSignal(
                        assignment.callId(),
                        rerouteSequence.getAndIncrement(),
                        timestamp,
                        assignment.operatorId().value(),
                        assignment.bridgeEndpoint());
        audioRerouteSignalPort.sendRerouteSignal(rerouteSignal);
    }

    /** Broadcasts a handoff lifecycle state transition to operator console listeners. */
    public void notifyLifecycleState(HandoffLifecycleState state) {
        operatorDispatchPort.notifyHandoffStateChanged(state);
    }

    /** Broadcasts updated priority queue depth metrics for a skill group. */
    public void broadcastQueueDepth(SkillGroup skillGroup, long queueDepth) {
        operatorDispatchPort.broadcastQueueMetrics(skillGroup, queueDepth);
    }
}
