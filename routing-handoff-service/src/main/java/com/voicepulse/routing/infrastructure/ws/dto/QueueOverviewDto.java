package com.voicepulse.routing.infrastructure.ws.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** DTO representing per-skill queue depths and waiting call items for the Operator Web Console. */
public record QueueOverviewDto(
        String selectedSkillGroup,
        long totalQueued,
        Map<String, Long> depthsBySkillGroup,
        List<QueuedTicketDto> queuedCalls,
        Instant generatedAt) {

    public QueueOverviewDto {
        Objects.requireNonNull(selectedSkillGroup, "selectedSkillGroup cannot be null");
        Objects.requireNonNull(generatedAt, "generatedAt cannot be null");
        depthsBySkillGroup = depthsBySkillGroup == null ? Map.of() : Map.copyOf(depthsBySkillGroup);
        queuedCalls = queuedCalls == null ? List.of() : List.copyOf(queuedCalls);
    }

    @Override
    public Map<String, Long> depthsBySkillGroup() {
        return depthsBySkillGroup == null ? Map.of() : Map.copyOf(depthsBySkillGroup);
    }

    @Override
    public List<QueuedTicketDto> queuedCalls() {
        return queuedCalls == null ? List.of() : List.copyOf(queuedCalls);
    }
}
