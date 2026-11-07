package com.voicepulse.shared.model;

/** Passenger rebooking preferences during flight disruption per RFC-0003. */
public enum RebookingPreference {
    NEXT_AVAILABLE,
    REFUND_REQUESTED,
    SAME_DAY_LATER
}
