package com.voicepulse.routing.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.voicepulse.routing.domain.model.HandoffAssignment;
import com.voicepulse.routing.domain.model.OperatorAvailabilityStatus;
import com.voicepulse.routing.domain.model.OperatorId;
import com.voicepulse.routing.domain.model.OperatorPresence;
import com.voicepulse.routing.domain.model.SkillGroup;
import com.voicepulse.routing.domain.ports.outbound.OperatorPresencePort;
import com.voicepulse.shared.model.CallId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("OperatorPresenceService Domain Unit Tests")
class OperatorPresenceServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-15T00:15:00Z");

    @Mock private OperatorPresencePort operatorPresencePort;
    @Mock private QueuedCallDrainService queuedCallDrainService;

    @Captor private ArgumentCaptor<OperatorPresence> presenceCaptor;

    private OperatorPresenceService service;

    @BeforeEach
    void setUp() {
        service = new OperatorPresenceService(operatorPresencePort, queuedCallDrainService);
    }

    @Test
    @DisplayName("Should drain queued call and bind activeCallId when operator becomes AVAILABLE")
    void shouldDrainQueueWhenTransitioningToAvailable() {
        OperatorId operatorId = OperatorId.fromString("op-pres-1");
        Set<SkillGroup> skills = Set.of(SkillGroup.DISRUPTION_DESK_PRIORITY);
        CallId callId = CallId.generate();
        HandoffAssignment assignment =
                new HandoffAssignment(
                        callId,
                        UUID.randomUUID(),
                        operatorId,
                        SkillGroup.DISRUPTION_DESK_PRIORITY,
                        "/ws/operators/op-pres-1/calls/" + callId + "/audio",
                        NOW);

        when(operatorPresencePort.findPresence(operatorId)).thenReturn(Optional.empty());
        when(queuedCallDrainService.drainNextForSkills(operatorId, skills, NOW))
                .thenReturn(Optional.of(assignment));

        OperatorPresence result =
                service.updatePresence(
                        operatorId,
                        "Priority Agent",
                        OperatorAvailabilityStatus.AVAILABLE,
                        skills,
                        NOW);

        verify(operatorPresencePort).savePresence(presenceCaptor.capture());
        assertThat(presenceCaptor.getValue().status())
                .isEqualTo(OperatorAvailabilityStatus.AVAILABLE);
        assertThat(result.status()).isEqualTo(OperatorAvailabilityStatus.BUSY);
        assertThat(result.activeCallId()).contains(callId);
    }

    @Test
    @DisplayName("Should retain activeCallId when switching from BUSY to PAUSED without draining")
    void shouldRetainActiveCallOnNonAvailableTransition() {
        OperatorId operatorId = OperatorId.fromString("op-pres-2");
        CallId activeCallId = CallId.generate();
        OperatorPresence existing =
                new OperatorPresence(
                        operatorId,
                        "Busy Agent",
                        OperatorAvailabilityStatus.BUSY,
                        Set.of(SkillGroup.DISRUPTION_DESK_STANDARD),
                        Optional.of(activeCallId),
                        NOW.minusSeconds(30));

        when(operatorPresencePort.findPresence(operatorId)).thenReturn(Optional.of(existing));

        OperatorPresence updated =
                service.updatePresence(
                        operatorId,
                        "Busy Agent",
                        OperatorAvailabilityStatus.PAUSED,
                        Set.of(SkillGroup.DISRUPTION_DESK_STANDARD),
                        NOW);

        assertThat(updated.status()).isEqualTo(OperatorAvailabilityStatus.PAUSED);
        assertThat(updated.activeCallId()).contains(activeCallId);
        verifyNoInteractions(queuedCallDrainService);
    }

    @Test
    @DisplayName("Should refresh heartbeat and delegate lookup queries")
    void shouldRefreshHeartbeatAndQueryPresence() {
        OperatorId operatorId = OperatorId.fromString("op-pres-3");
        OperatorPresence existing =
                new OperatorPresence(
                        operatorId,
                        "Agent 3",
                        OperatorAvailabilityStatus.AVAILABLE,
                        Set.of(SkillGroup.DISRUPTION_DESK_GOLD),
                        Optional.empty(),
                        NOW.minusSeconds(10));

        when(operatorPresencePort.refreshHeartbeat(operatorId, NOW)).thenReturn(true);
        when(operatorPresencePort.findPresence(operatorId)).thenReturn(Optional.of(existing));
        when(operatorPresencePort.findBySkillGroup(SkillGroup.DISRUPTION_DESK_GOLD))
                .thenReturn(List.of(existing));

        Optional<OperatorPresence> refreshed = service.refreshHeartbeat(operatorId, NOW);
        assertThat(refreshed).isPresent();
        assertThat(refreshed.get().lastHeartbeatAt()).isEqualTo(NOW);
        assertThat(service.getPresence(operatorId)).contains(existing);
        assertThat(service.listOperatorsBySkillGroup(SkillGroup.DISRUPTION_DESK_GOLD))
                .containsExactly(existing);

        OperatorId expiredOperator = OperatorId.fromString("op-expired");
        when(operatorPresencePort.refreshHeartbeat(expiredOperator, NOW)).thenReturn(false);
        assertThat(service.refreshHeartbeat(expiredOperator, NOW)).isEmpty();
        verify(operatorPresencePort, never()).findPresence(expiredOperator);
        verify(queuedCallDrainService, never()).drainNextForSkills(any(), any(), any());
    }
}
