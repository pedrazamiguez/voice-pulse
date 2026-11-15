package com.voicepulse.routing.infrastructure.ws.dto;

import java.util.Set;

/** REST request DTO for registering or updating operator availability status and skill groups. */
public record OperatorPresenceRequestDto(
        String displayName, String status, Set<String> skillGroups) {

    public OperatorPresenceRequestDto {
        skillGroups = skillGroups == null ? Set.of() : Set.copyOf(skillGroups);
    }

    @Override
    public Set<String> skillGroups() {
        return skillGroups == null ? Set.of() : Set.copyOf(skillGroups);
    }
}
