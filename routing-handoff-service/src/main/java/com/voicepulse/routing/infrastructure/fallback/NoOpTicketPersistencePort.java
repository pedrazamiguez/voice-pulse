package com.voicepulse.routing.infrastructure.fallback;

import com.voicepulse.routing.domain.model.HandoffLifecycleState;
import com.voicepulse.routing.domain.model.OperatorId;
import com.voicepulse.routing.domain.model.SkillGroup;
import com.voicepulse.routing.domain.ports.outbound.TicketPersistencePort;
import com.voicepulse.shared.event.TicketFormFilledEvent;
import com.voicepulse.shared.model.CallId;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Fallback;
import org.springframework.stereotype.Component;

/** Fallback no-op implementation of {@link TicketPersistencePort} (Null Object Pattern). */
@Slf4j
@Fallback
@Component
public class NoOpTicketPersistencePort implements TicketPersistencePort {

    @Override
    public void saveTicket(
            TicketFormFilledEvent event,
            SkillGroup skillGroup,
            HandoffLifecycleState initialState) {
        log.debug(
                "No-op saveTicket: callId={}, ticketId={}, skillGroup={}",
                event.callId(),
                event.ticketId(),
                skillGroup);
    }

    @Override
    public void updateTicketStatus(UUID ticketId, String status, Instant updatedAt) {
        log.debug("No-op updateTicketStatus: ticketId={}, status={}", ticketId, status);
    }

    @Override
    public void recordHandoffAuditLog(
            CallId callId,
            String eventType,
            String sourceState,
            String targetState,
            OperatorId operatorId,
            Map<String, Object> details,
            Instant occurredAt) {
        log.debug(
                "No-op recordHandoffAuditLog: callId={}, eventType={}, source={}, target={}",
                callId,
                eventType,
                sourceState,
                targetState);
    }

    @Override
    public Optional<TicketFormFilledEvent> findTicketByCallId(CallId callId) {
        log.debug("No-op findTicketByCallId: callId={}", callId);
        return Optional.empty();
    }
}
