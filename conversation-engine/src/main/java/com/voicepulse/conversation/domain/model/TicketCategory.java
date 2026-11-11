package com.voicepulse.conversation.domain.model;

import com.voicepulse.shared.model.RebookingPreference;

/** Pure domain enumeration of airline disruption ticket categories (RFC-0003 §4.2). */
public enum TicketCategory {
    HOTEL_ACCOMMODATION,
    REFUND_DISRUPTION,
    FLIGHT_REBOOKING,
    DISRUPTION_TRIAGE;

    /**
     * Resolves the canonical ticket category from passenger accommodation and rebooking
     * preferences.
     *
     * @param hotelRequired true if overnight hotel accommodation is requested
     * @param rebookingPreference passenger rebooking or refund preference
     * @return resolved {@link TicketCategory}
     */
    public static TicketCategory resolve(
            boolean hotelRequired, RebookingPreference rebookingPreference) {
        if (hotelRequired) {
            return HOTEL_ACCOMMODATION;
        }
        if (rebookingPreference == RebookingPreference.REFUND_REQUESTED) {
            return REFUND_DISRUPTION;
        }
        return FLIGHT_REBOOKING;
    }
}
