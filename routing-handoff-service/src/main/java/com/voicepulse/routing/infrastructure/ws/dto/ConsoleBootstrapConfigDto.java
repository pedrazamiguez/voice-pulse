package com.voicepulse.routing.infrastructure.ws.dto;

import java.util.List;
import java.util.Objects;

/** Bootstrap configuration exposed by {@code GET /api/operators/console/config}. */
public record ConsoleBootstrapConfigDto(
        String gatewayWsBaseUrl,
        long heartbeatIntervalMs,
        List<String> skillGroups,
        List<String> availabilityStatuses) {

    public ConsoleBootstrapConfigDto {
        Objects.requireNonNull(gatewayWsBaseUrl, "gatewayWsBaseUrl cannot be null");
        skillGroups = skillGroups == null ? List.of() : List.copyOf(skillGroups);
        availabilityStatuses =
                availabilityStatuses == null ? List.of() : List.copyOf(availabilityStatuses);
    }

    @Override
    public List<String> skillGroups() {
        return skillGroups == null ? List.of() : List.copyOf(skillGroups);
    }

    @Override
    public List<String> availabilityStatuses() {
        return availabilityStatuses == null ? List.of() : List.copyOf(availabilityStatuses);
    }
}
