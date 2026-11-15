package com.voicepulse.routing.infrastructure.fallback;

import com.voicepulse.routing.domain.model.OperatorAvailabilityStatus;
import com.voicepulse.routing.domain.model.OperatorId;
import com.voicepulse.routing.domain.model.OperatorPresence;
import com.voicepulse.routing.domain.model.SkillGroup;
import com.voicepulse.routing.domain.ports.outbound.OperatorPresencePort;
import com.voicepulse.shared.model.CallId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Fallback;
import org.springframework.stereotype.Component;

/** Fallback no-op implementation of {@link OperatorPresencePort} (Null Object Pattern). */
@Slf4j
@Fallback
@Component
public class NoOpOperatorPresencePort implements OperatorPresencePort {

    @Override
    public void savePresence(OperatorPresence presence) {
        log.debug("No-op savePresence: presence={}", presence);
    }

    @Override
    public Optional<OperatorPresence> findPresence(OperatorId operatorId) {
        log.debug("No-op findPresence: operatorId={}", operatorId);
        return Optional.empty();
    }

    @Override
    public List<OperatorPresence> findAvailableBySkillGroup(SkillGroup skillGroup) {
        log.debug("No-op findAvailableBySkillGroup: skillGroup={}", skillGroup);
        return List.of();
    }

    @Override
    public List<OperatorPresence> findBySkillGroup(SkillGroup skillGroup) {
        log.debug("No-op findBySkillGroup: skillGroup={}", skillGroup);
        return List.of();
    }

    @Override
    public boolean tryClaimOperator(OperatorId operatorId, CallId callId, Instant claimedAt) {
        log.debug("No-op tryClaimOperator: operatorId={}, callId={}", operatorId, callId);
        return false;
    }

    @Override
    public void releaseOperator(
            OperatorId operatorId, OperatorAvailabilityStatus nextStatus, Instant releasedAt) {
        log.debug("No-op releaseOperator: operatorId={}, nextStatus={}", operatorId, nextStatus);
    }

    @Override
    public boolean refreshHeartbeat(OperatorId operatorId, Instant heartbeatAt) {
        log.debug("No-op refreshHeartbeat: operatorId={}, heartbeatAt={}", operatorId, heartbeatAt);
        return false;
    }
}
