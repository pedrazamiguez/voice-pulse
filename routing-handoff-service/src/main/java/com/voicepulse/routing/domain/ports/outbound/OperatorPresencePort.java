package com.voicepulse.routing.domain.ports.outbound;

import com.voicepulse.routing.domain.model.OperatorAvailabilityStatus;
import com.voicepulse.routing.domain.model.OperatorId;
import com.voicepulse.routing.domain.model.OperatorPresence;
import com.voicepulse.routing.domain.model.SkillGroup;
import com.voicepulse.shared.model.CallId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Driven (outbound) SPI port for tracking operator presence, heartbeats, and atomic call claiming
 * in Redis.
 */
public interface OperatorPresencePort {

    /**
     * Persists or updates an operator presence record and its skill-group memberships.
     *
     * @param presence operator presence snapshot
     */
    void savePresence(OperatorPresence presence);

    /**
     * Retrieves an operator's presence snapshot.
     *
     * @param operatorId operator identifier
     * @return presence if present and heartbeat is active
     */
    Optional<OperatorPresence> findPresence(OperatorId operatorId);

    /**
     * Lists currently {@code AVAILABLE} operators belonging to the target skill group.
     *
     * @param skillGroup target skill group
     * @return available operators
     */
    List<OperatorPresence> findAvailableBySkillGroup(SkillGroup skillGroup);

    /**
     * Lists all registered operators belonging to the target skill group regardless of status.
     *
     * @param skillGroup target skill group
     * @return operators in the skill group
     */
    List<OperatorPresence> findBySkillGroup(SkillGroup skillGroup);

    /**
     * Atomically transitions an {@code AVAILABLE} operator to {@code BUSY} bound to {@code callId}.
     *
     * @param operatorId target operator identifier
     * @param callId call being assigned
     * @param claimedAt claim timestamp
     * @return {@code true} if the operator was available and atomically claimed; {@code false}
     *     otherwise
     */
    boolean tryClaimOperator(OperatorId operatorId, CallId callId, Instant claimedAt);

    /**
     * Releases an operator's active call binding and sets their status to {@code nextStatus}.
     *
     * @param operatorId operator identifier
     * @param nextStatus target availability status after release
     * @param releasedAt release timestamp
     */
    void releaseOperator(
            OperatorId operatorId, OperatorAvailabilityStatus nextStatus, Instant releasedAt);

    /**
     * Refreshes an operator's heartbeat TTL.
     *
     * @param operatorId operator identifier
     * @param heartbeatAt heartbeat timestamp
     * @return {@code true} if the operator presence exists and TTL was refreshed
     */
    boolean refreshHeartbeat(OperatorId operatorId, Instant heartbeatAt);
}
