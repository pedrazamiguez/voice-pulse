package com.voicepulse.routing.domain.model;

/** Canonical audit event classifications recorded in {@code operator_handoff_logs}. */
public enum HandoffAuditEventType {
    TICKET_QUEUED,
    OPERATOR_ASSIGNED,
    HANDOFF_COMPLETED,
    CALL_ABANDONED
}
