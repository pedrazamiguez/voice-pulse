package com.voicepulse.routing.infrastructure.ws.dto;

import java.time.Instant;
import java.util.Set;

/** REST & WebSocket DTO representing an operator's live presence and active call binding. */
public record OperatorPresenceDto(
        String operatorId,
        String displayName,
        String status,
        Set<String> skillGroups,
        String activeCallId,
        Instant lastHeartbeatAt) {

    public OperatorPresenceDto {
        skillGroups = skillGroups == null ? Set.of() : Set.copyOf(skillGroups);
    }

    @Override
    public Set<String> skillGroups() {
        return skillGroups == null ? Set.of() : Set.copyOf(skillGroups);
    }
}
