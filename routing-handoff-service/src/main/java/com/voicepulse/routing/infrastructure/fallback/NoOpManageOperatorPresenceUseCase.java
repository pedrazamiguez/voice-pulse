package com.voicepulse.routing.infrastructure.fallback;

import com.voicepulse.routing.domain.model.OperatorAvailabilityStatus;
import com.voicepulse.routing.domain.model.OperatorId;
import com.voicepulse.routing.domain.model.OperatorPresence;
import com.voicepulse.routing.domain.model.SkillGroup;
import com.voicepulse.routing.domain.ports.inbound.ManageOperatorPresenceUseCase;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Fallback;
import org.springframework.stereotype.Component;

/** Fallback no-op implementation of {@link ManageOperatorPresenceUseCase} (Null Object Pattern). */
@Slf4j
@Fallback
@Component
public class NoOpManageOperatorPresenceUseCase implements ManageOperatorPresenceUseCase {

    @Override
    public OperatorPresence updatePresence(
            OperatorId operatorId,
            String displayName,
            OperatorAvailabilityStatus status,
            Set<SkillGroup> skills,
            Instant timestamp) {
        Objects.requireNonNull(operatorId, "operatorId cannot be null");
        Objects.requireNonNull(status, "status cannot be null");
        Objects.requireNonNull(timestamp, "timestamp cannot be null");
        log.debug(
                "No-op updatePresence: operatorId={}, status={}, skills={}",
                operatorId,
                status,
                skills);
        return new OperatorPresence(
                operatorId, displayName, status, skills, Optional.empty(), timestamp);
    }

    @Override
    public Optional<OperatorPresence> refreshHeartbeat(OperatorId operatorId, Instant timestamp) {
        log.debug("No-op refreshHeartbeat: operatorId={}, timestamp={}", operatorId, timestamp);
        return Optional.empty();
    }

    @Override
    public Optional<OperatorPresence> getPresence(OperatorId operatorId) {
        log.debug("No-op getPresence: operatorId={}", operatorId);
        return Optional.empty();
    }

    @Override
    public List<OperatorPresence> listOperatorsBySkillGroup(SkillGroup skillGroup) {
        log.debug("No-op listOperatorsBySkillGroup: skillGroup={}", skillGroup);
        return List.of();
    }
}
