package com.voicepulse.conversation.infrastructure.fallback;

import com.voicepulse.conversation.domain.ports.outbound.TicketIntentPublisherPort;
import com.voicepulse.shared.event.TicketFormFilledEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Fallback;
import org.springframework.stereotype.Component;

/**
 * Fallback no-op implementation of {@link TicketIntentPublisherPort} (Null Object Pattern).
 *
 * <p>Registered as a Spring {@link Fallback} bean so that it is automatically superseded once the
 * concrete Kafka ticket intent publisher adapter is registered.
 */
@Slf4j
@Fallback
@Component
public class NoOpTicketIntentPublisherPort implements TicketIntentPublisherPort {

    @Override
    public void publishTicketFilled(TicketFormFilledEvent event) {
        log.debug(
                "No-op TicketIntentPublisherPort publishing ticket filled: callId={}, ticketId={},"
                        + " skillGroup={}",
                event.callId(),
                event.ticketId(),
                event.targetSkillGroup());
    }
}
