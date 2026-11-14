package com.voicepulse.conversation.domain.model;

/** Pure domain enumeration of airline disruption ticket priorities (RFC-0003 §4.2). */
public enum TicketPriority {
    HIGH,
    NORMAL;

    /**
     * Resolves ticket priority based on hotel and special assistance flags.
     *
     * @param hotelRequired true if overnight hotel accommodation is requested
     * @param specialAssistance true if wheelchair or accessibility assistance is requested
     * @return resolved {@link TicketPriority}
     */
    public static TicketPriority resolve(boolean hotelRequired, boolean specialAssistance) {
        return (hotelRequired || specialAssistance) ? HIGH : NORMAL;
    }
}
