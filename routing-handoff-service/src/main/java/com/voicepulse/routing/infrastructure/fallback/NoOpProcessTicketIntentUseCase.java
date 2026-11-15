package com.voicepulse.routing.infrastructure.fallback;

import com.voicepulse.routing.domain.model.HandoffLifecycleState;
import com.voicepulse.routing.domain.model.SkillGroup;
import com.voicepulse.routing.domain.ports.inbound.ProcessTicketIntentUseCase;
import com.voicepulse.shared.event.TicketFormFilledEvent;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Fallback;
import org.springframework.stereotype.Component;

/**
 * Fallback no-op implementation of {@link ProcessTicketIntentUseCase} (Null Object Pattern).
 *
 * <p>Automatically superseded once the domain assignment coordinator bean is registered.
 */
@Slf4j
@Fallback
@Component
public class NoOpProcessTicketIntentUseCase implements ProcessTicketIntentUseCase {

    @Override
    public HandoffLifecycleState processTicketIntent(TicketFormFilledEvent event) {
        Objects.requireNonNull(event, "event cannot be null");
        log.debug(
                "No-op ProcessTicketIntentUseCase invoked: callId={}, ticketId={},"
                        + " targetSkillGroup={}",
                event.callId(),
                event.ticketId(),
                event.targetSkillGroup());
        return new HandoffLifecycleState.Queued(
                event.callId(),
                event.ticketId(),
                SkillGroup.DISRUPTION_DESK_STANDARD,
                0,
                event.timestamp());
    }
}
