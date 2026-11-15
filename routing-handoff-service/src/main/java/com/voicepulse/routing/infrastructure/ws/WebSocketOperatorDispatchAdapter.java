package com.voicepulse.routing.infrastructure.ws;

import com.voicepulse.routing.domain.model.HandoffAssignment;
import com.voicepulse.routing.domain.model.HandoffLifecycleState;
import com.voicepulse.routing.domain.model.SkillGroup;
import com.voicepulse.routing.domain.ports.outbound.OperatorDispatchPort;
import com.voicepulse.routing.infrastructure.ws.config.OperatorConsoleProperties;
import com.voicepulse.routing.infrastructure.ws.dto.HandoffStateDto;
import com.voicepulse.routing.infrastructure.ws.dto.OperatorAssignmentPayloadDto;
import com.voicepulse.routing.infrastructure.ws.dto.OperatorWsMessage;
import com.voicepulse.routing.infrastructure.ws.mapper.OperatorConsoleMapper;
import com.voicepulse.routing.infrastructure.ws.mapper.TranscriptContextExtractor;
import com.voicepulse.shared.event.TicketFormFilledEvent;
import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Outbound reactive WebSocket adapter implementing {@link OperatorDispatchPort}.
 *
 * <p>Pushes pre-populated disruption ticket assignments, queue depth metrics, and handoff lifecycle
 * state updates to connected Operator Web Console sessions via {@link
 * OperatorConsoleSessionRegistry}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebSocketOperatorDispatchAdapter implements OperatorDispatchPort {

    private final @NonNull OperatorConsoleSessionRegistry sessionRegistry;
    private final @NonNull OperatorConsoleMapper mapper;
    private final @NonNull TranscriptContextExtractor transcriptContextExtractor;
    private final @NonNull OperatorConsoleProperties consoleProperties;
    private final @NonNull Clock clock;

    private final ConcurrentHashMap<String, Long> latestQueueDepths = new ConcurrentHashMap<>();

    @Override
    public void pushAssignment(HandoffAssignment assignment, TicketFormFilledEvent ticketEvent) {
        Objects.requireNonNull(assignment, "assignment cannot be null");
        Objects.requireNonNull(ticketEvent, "ticketEvent cannot be null");

        OperatorAssignmentPayloadDto payload =
                mapper.toAssignmentPayload(
                        assignment, ticketEvent, consoleProperties, transcriptContextExtractor);
        sessionRegistry.cacheActiveAssignment(assignment.operatorId(), payload);

        OperatorWsMessage message =
                new OperatorWsMessage.AssignmentPushedMessage(payload, clock.instant());
        sessionRegistry.sendToOperator(assignment.operatorId(), message);

        log.info(
                "Dispatched assignment to Operator Web Console: operatorId={}, callId={},"
                        + " ticketId={}, skillGroup={}, activeConnections={}",
                assignment.operatorId(),
                assignment.callId(),
                assignment.ticketId(),
                assignment.skillGroup(),
                sessionRegistry.activeConnectionCount(assignment.operatorId()));
    }

    @Override
    public void broadcastQueueMetrics(SkillGroup skillGroup, long queueDepth) {
        Objects.requireNonNull(skillGroup, "skillGroup cannot be null");
        long normalizedDepth = Math.max(0L, queueDepth);
        latestQueueDepths.put(skillGroup.name(), normalizedDepth);

        OperatorWsMessage message =
                new OperatorWsMessage.QueueMetricsMessage(
                        skillGroup.name(),
                        normalizedDepth,
                        Map.copyOf(latestQueueDepths),
                        clock.instant());
        sessionRegistry.broadcast(message);

        log.debug(
                "Broadcasted queue metrics to Operator Web Consoles: skillGroup={}, depth={},"
                        + " totalConnections={}",
                skillGroup,
                normalizedDepth,
                sessionRegistry.totalActiveConnections());
    }

    @Override
    public void notifyHandoffStateChanged(HandoffLifecycleState state) {
        Objects.requireNonNull(state, "state cannot be null");
        HandoffStateDto stateDto = mapper.toHandoffStateDto(state, consoleProperties);
        OperatorWsMessage message =
                new OperatorWsMessage.HandoffStateChangedMessage(stateDto, clock.instant());

        switch (state) {
            case HandoffLifecycleState.Assigned assigned ->
                    sessionRegistry.sendToOperator(assigned.operatorId(), message);
            case HandoffLifecycleState.AudioBridged bridged ->
                    sessionRegistry.sendToOperator(bridged.operatorId(), message);
            case HandoffLifecycleState.Completed completed -> {
                sessionRegistry.evictActiveAssignment(completed.operatorId(), completed.callId());
                sessionRegistry.sendToOperator(completed.operatorId(), message);
            }
            case HandoffLifecycleState.Queued queued -> {
                log.debug("Broadcasted queued handoff state: callId={}", queued.callId());
                sessionRegistry.broadcast(message);
            }
            case HandoffLifecycleState.Abandoned abandoned -> {
                sessionRegistry.evictActiveAssignmentByCallId(abandoned.callId());
                sessionRegistry.broadcast(message);
            }
            default ->
                    throw new IllegalStateException("Unexpected HandoffLifecycleState: " + state);
        }
    }
}
