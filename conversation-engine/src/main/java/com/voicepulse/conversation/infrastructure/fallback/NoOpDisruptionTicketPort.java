package com.voicepulse.conversation.infrastructure.fallback;

import com.voicepulse.conversation.domain.model.AirlineDisruptionTicket;
import com.voicepulse.conversation.domain.model.TicketCategory;
import com.voicepulse.conversation.domain.model.TicketPriority;
import com.voicepulse.conversation.domain.model.TicketSubmissionReceipt;
import com.voicepulse.conversation.domain.ports.outbound.DisruptionTicketPort;
import com.voicepulse.shared.model.Pnr;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Fallback;
import org.springframework.stereotype.Component;

/**
 * Fallback no-op implementation of {@link DisruptionTicketPort} (Null Object Pattern).
 *
 * <p>Registered as a Spring {@link Fallback} bean so that it is automatically superseded once the
 * concrete {@code mcp-ticket-server} client adapter is registered.
 */
@Slf4j
@Fallback
@Component
public class NoOpDisruptionTicketPort implements DisruptionTicketPort {

    private static final String FALLBACK_STATUS = "NO_OP_FALLBACK";

    @Override
    public TicketSubmissionReceipt finalizeTicket(AirlineDisruptionTicket ticket) {
        log.debug(
                "No-op DisruptionTicketPort finalizing ticket: ticketId={}, pnr={}",
                ticket.ticketId(),
                ticket.pnr());
        return new TicketSubmissionReceipt(
                ticket.ticketId().toString(),
                new Pnr(ticket.pnr()),
                TicketCategory.DISRUPTION_TRIAGE.name(),
                TicketPriority.NORMAL.name(),
                FALLBACK_STATUS,
                ticket.updatedAt());
    }
}
