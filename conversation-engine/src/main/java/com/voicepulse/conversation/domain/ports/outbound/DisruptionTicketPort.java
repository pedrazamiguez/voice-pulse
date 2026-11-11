package com.voicepulse.conversation.domain.ports.outbound;

import com.voicepulse.conversation.domain.model.AirlineDisruptionTicket;
import com.voicepulse.conversation.domain.model.TicketSubmissionReceipt;

/**
 * Driven (outbound) port for validating and finalizing compiled airline disruption tickets via
 * {@code mcp-ticket-server} (RFC-0001 §4.4 & RFC-0003 §4.2).
 */
public interface DisruptionTicketPort {

    /**
     * Commits a compiled airline disruption ticket for operator handoff.
     *
     * @param ticket compiled disruption ticket domain record
     * @return ticket submission receipt from the MCP ticket server
     */
    TicketSubmissionReceipt finalizeTicket(AirlineDisruptionTicket ticket);
}
