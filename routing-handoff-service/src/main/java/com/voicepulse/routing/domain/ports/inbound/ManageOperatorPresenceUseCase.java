package com.voicepulse.routing.domain.ports.inbound;

import com.voicepulse.routing.domain.model.OperatorAvailabilityStatus;
import com.voicepulse.routing.domain.model.OperatorId;
import com.voicepulse.routing.domain.model.OperatorPresence;
import com.voicepulse.routing.domain.model.SkillGroup;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Driving (inbound) port for registering operator presence, updating availability status, and
 * refreshing operator heartbeats from the Operator Web Console.
 */
public interface ManageOperatorPresenceUseCase {

    /**
     * Registers or updates an operator's availability status and skill memberships.
     *
     * @param operatorId operator identifier
     * @param displayName operator display name
     * @param status target availability status
     * @param skills assigned skill groups
     * @param timestamp update timestamp
     * @return updated operator presence snapshot
     */
    OperatorPresence updatePresence(
            OperatorId operatorId,
            String displayName,
            OperatorAvailabilityStatus status,
            Set<SkillGroup> skills,
            Instant timestamp);

    /**
     * Refreshes an active operator's presence heartbeat TTL.
     *
     * @param operatorId operator identifier
     * @param timestamp heartbeat timestamp
     * @return refreshed presence if the operator is registered
     */
    Optional<OperatorPresence> refreshHeartbeat(OperatorId operatorId, Instant timestamp);

    /**
     * Retrieves the current presence snapshot for an operator.
     *
     * @param operatorId operator identifier
     * @return current presence if registered
     */
    Optional<OperatorPresence> getPresence(OperatorId operatorId);

    /**
     * Lists all operators registered under a given skill group.
     *
     * @param skillGroup target skill group
     * @return operators belonging to the skill group
     */
    List<OperatorPresence> listOperatorsBySkillGroup(SkillGroup skillGroup);
}
