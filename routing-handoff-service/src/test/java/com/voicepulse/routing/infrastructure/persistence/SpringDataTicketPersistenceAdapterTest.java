package com.voicepulse.routing.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.voicepulse.routing.domain.model.HandoffLifecycleState;
import com.voicepulse.routing.domain.model.OperatorId;
import com.voicepulse.routing.domain.model.SkillGroup;
import com.voicepulse.routing.infrastructure.persistence.mapper.TicketPersistenceMapper;
import com.voicepulse.routing.infrastructure.persistence.model.CallSessionEntity;
import com.voicepulse.routing.infrastructure.persistence.model.DisruptionTicketEntity;
import com.voicepulse.routing.infrastructure.persistence.model.JsonbDocument;
import com.voicepulse.routing.infrastructure.persistence.model.OperatorHandoffLogEntity;
import com.voicepulse.routing.infrastructure.persistence.repository.CallSessionRepository;
import com.voicepulse.routing.infrastructure.persistence.repository.DisruptionTicketRepository;
import com.voicepulse.routing.infrastructure.persistence.repository.OperatorHandoffLogRepository;
import com.voicepulse.shared.event.TicketFormFilledEvent;
import com.voicepulse.shared.model.CallId;
import com.voicepulse.shared.model.FlightNumber;
import com.voicepulse.shared.model.Pnr;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.instancio.Instancio;
import org.instancio.Select;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.jdbc.core.JdbcAggregateOperations;
import org.springframework.data.relational.core.conversion.DbAction;
import org.springframework.data.relational.core.conversion.DbActionExecutionException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

@ExtendWith(MockitoExtension.class)
@DisplayName("SpringDataTicketPersistenceAdapter Unit Tests")
class SpringDataTicketPersistenceAdapterTest {

    @Mock private CallSessionRepository callSessionRepository;
    @Mock private DisruptionTicketRepository disruptionTicketRepository;
    @Mock private OperatorHandoffLogRepository operatorHandoffLogRepository;
    @Mock private JdbcAggregateOperations jdbcAggregateOperations;
    @Mock private PlatformTransactionManager transactionManager;
    @Mock private TransactionStatus transactionStatus;

    @Captor private ArgumentCaptor<CallSessionEntity> callSessionCaptor;
    @Captor private ArgumentCaptor<DisruptionTicketEntity> ticketCaptor;
    @Captor private ArgumentCaptor<OperatorHandoffLogEntity> auditLogCaptor;

    private ExecutorService jdbcVirtualThreadExecutor;
    private TicketPersistenceMapper mapper;
    private SpringDataTicketPersistenceAdapter adapter;

    @BeforeEach
    void setUp() {
        jdbcVirtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
        mapper = Mappers.getMapper(TicketPersistenceMapper.class);
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        adapter =
                new SpringDataTicketPersistenceAdapter(
                        callSessionRepository,
                        disruptionTicketRepository,
                        operatorHandoffLogRepository,
                        jdbcAggregateOperations,
                        transactionTemplate,
                        jdbcVirtualThreadExecutor,
                        mapper);
    }

    @AfterEach
    void tearDown() {
        jdbcVirtualThreadExecutor.close();
    }

    @Test
    @DisplayName("Should insert new session and ticket on virtual thread when records do not exist")
    void shouldInsertNewSessionAndTicketOnVirtualThread() {
        stubTransactionCommit();
        TicketFormFilledEvent event =
                createTicketEvent(
                        SkillGroup.DISRUPTION_DESK_GOLD, Map.of("caller_number", "+15551112222"));
        HandoffLifecycleState.Queued initialState =
                new HandoffLifecycleState.Queued(
                        event.callId(),
                        event.ticketId(),
                        SkillGroup.DISRUPTION_DESK_GOLD,
                        100,
                        event.timestamp());

        AtomicBoolean ranOnVirtualThread = new AtomicBoolean(false);
        when(callSessionRepository.findById(event.callId().value())).thenReturn(Optional.empty());
        when(disruptionTicketRepository.findById(event.ticketId().toString()))
                .thenReturn(Optional.empty());
        when(jdbcAggregateOperations.insert(any()))
                .thenAnswer(
                        invocation -> {
                            ranOnVirtualThread.set(Thread.currentThread().isVirtual());
                            return invocation.getArgument(0);
                        });

        adapter.saveTicket(event, SkillGroup.DISRUPTION_DESK_GOLD, initialState);

        assertThat(ranOnVirtualThread).isTrue();
        verify(jdbcAggregateOperations).insert(callSessionCaptor.capture());
        verify(jdbcAggregateOperations).insert(ticketCaptor.capture());
        verify(callSessionRepository, never()).save(any());
        verify(disruptionTicketRepository, never()).save(any());
        verify(transactionManager).commit(transactionStatus);

        assertThat(callSessionCaptor.getValue().callId()).isEqualTo(event.callId().value());
        assertThat(callSessionCaptor.getValue().metadata().entries())
                .containsEntry("targetSkillGroup", "DISRUPTION_DESK_GOLD");
        assertThat(ticketCaptor.getValue().ticketId()).isEqualTo(event.ticketId().toString());
        assertThat(ticketCaptor.getValue().status()).isEqualTo("QUEUED");
    }

    @Test
    @DisplayName(
            "Should merge existing session metadata and preserve createdAt when updating ticket")
    void shouldMergeExistingSessionAndPreserveTicketCreatedAt() {
        stubTransactionCommit();
        Instant originalCreatedAt = Instant.parse("2026-11-10T07:00:00Z");
        Instant incomingTimestamp = Instant.parse("2026-11-10T07:05:00Z");
        TicketFormFilledEvent event =
                createTicketEvent(
                        SkillGroup.DISRUPTION_DESK_ACCESSIBILITY,
                        Map.of("wheelchair", true, "priority", "HIGH"),
                        incomingTimestamp);
        HandoffLifecycleState.Assigned assignedState =
                new HandoffLifecycleState.Assigned(
                        event.callId(),
                        event.ticketId(),
                        OperatorId.fromString("op-42"),
                        SkillGroup.DISRUPTION_DESK_ACCESSIBILITY,
                        incomingTimestamp);

        CallSessionEntity existingSession =
                new CallSessionEntity(
                        event.callId().value(),
                        "OLD123",
                        "+15550009999",
                        "QUEUED",
                        originalCreatedAt,
                        null,
                        null,
                        null,
                        JsonbDocument.of(Map.of("origin", "voice", "targetSkillGroup", "OLD")),
                        originalCreatedAt,
                        originalCreatedAt);
        DisruptionTicketEntity existingTicket =
                mapper.toTicketEntity(event, SkillGroup.DISRUPTION_DESK_STANDARD, assignedState)
                        .withStatus("QUEUED", originalCreatedAt);
        existingTicket =
                DisruptionTicketEntity.mergePreservingCreatedAt(existingTicket, originalCreatedAt);

        when(callSessionRepository.findById(event.callId().value()))
                .thenReturn(Optional.of(existingSession));
        when(disruptionTicketRepository.findById(event.ticketId().toString()))
                .thenReturn(Optional.of(existingTicket));

        adapter.saveTicket(event, SkillGroup.DISRUPTION_DESK_ACCESSIBILITY, assignedState);

        verify(jdbcAggregateOperations, never()).insert(any());
        verify(callSessionRepository).save(callSessionCaptor.capture());
        verify(disruptionTicketRepository).save(ticketCaptor.capture());

        CallSessionEntity mergedSession = callSessionCaptor.getValue();
        assertThat(mergedSession.createdAt()).isEqualTo(originalCreatedAt);
        assertThat(mergedSession.startTime()).isEqualTo(originalCreatedAt);
        assertThat(mergedSession.assignedOperatorId()).isEqualTo("op-42");
        assertThat(mergedSession.metadata().entries())
                .containsEntry("origin", "voice")
                .containsEntry("targetSkillGroup", "DISRUPTION_DESK_ACCESSIBILITY")
                .containsEntry("resolvedSkillGroup", "DISRUPTION_DESK_ACCESSIBILITY");

        DisruptionTicketEntity savedTicket = ticketCaptor.getValue();
        assertThat(savedTicket.createdAt()).isEqualTo(originalCreatedAt);
        assertThat(savedTicket.updatedAt()).isEqualTo(incomingTimestamp);
        assertThat(savedTicket.status()).isEqualTo("ASSIGNED");
    }

    @Test
    @DisplayName(
            "Should roll back and retry once as update merge when concurrent insert hits"
                    + " DuplicateKey")
    void shouldRetrySaveTicketOnceOnConcurrentDuplicateKey() {
        stubTransactionCommit();
        TicketFormFilledEvent event = createTicketEvent(SkillGroup.DISRUPTION_DESK_GOLD, Map.of());
        HandoffLifecycleState.Queued state =
                new HandoffLifecycleState.Queued(
                        event.callId(),
                        event.ticketId(),
                        SkillGroup.DISRUPTION_DESK_GOLD,
                        90,
                        event.timestamp());
        CallSessionEntity winningSession =
                mapper.toCallSessionEntity(event, SkillGroup.DISRUPTION_DESK_GOLD, state);
        DisruptionTicketEntity winningTicket =
                mapper.toTicketEntity(event, SkillGroup.DISRUPTION_DESK_GOLD, state);

        AtomicInteger lookupCount = new AtomicInteger();
        when(callSessionRepository.findById(event.callId().value()))
                .thenAnswer(
                        ignored ->
                                lookupCount.getAndIncrement() == 0
                                        ? Optional.empty()
                                        : Optional.of(winningSession));
        when(disruptionTicketRepository.findById(event.ticketId().toString()))
                .thenReturn(Optional.of(winningTicket));
        DbAction<?> action = mock(DbAction.class);
        DbActionExecutionException duplicateFailure =
                new DbActionExecutionException(
                        action, new DuplicateKeyException("duplicate call_sessions pk"));
        when(jdbcAggregateOperations.insert(any(CallSessionEntity.class)))
                .thenThrow(duplicateFailure);

        adapter.saveTicket(event, SkillGroup.DISRUPTION_DESK_GOLD, state);

        verify(transactionManager, times(1)).rollback(transactionStatus);
        verify(transactionManager, times(1)).commit(transactionStatus);
        verify(callSessionRepository).save(any(CallSessionEntity.class));
        verify(disruptionTicketRepository).save(any(DisruptionTicketEntity.class));
    }

    @Test
    @DisplayName("Should wrap persistent DataAccessException into TicketPersistenceException")
    void shouldWrapDataAccessExceptionIntoTicketPersistenceException() {
        stubTransactionCommit();
        TicketFormFilledEvent event =
                createTicketEvent(SkillGroup.DISRUPTION_DESK_STANDARD, Map.of());
        HandoffLifecycleState.Queued state =
                new HandoffLifecycleState.Queued(
                        event.callId(),
                        event.ticketId(),
                        SkillGroup.DISRUPTION_DESK_STANDARD,
                        10,
                        event.timestamp());

        when(callSessionRepository.findById(event.callId().value()))
                .thenThrow(new DataIntegrityViolationException("constraint violation"));

        assertThatThrownBy(
                        () -> adapter.saveTicket(event, SkillGroup.DISRUPTION_DESK_STANDARD, state))
                .isInstanceOf(TicketPersistenceException.class)
                .hasCauseInstanceOf(DataIntegrityViolationException.class);
        verify(transactionManager).rollback(transactionStatus);
    }

    @Test
    @DisplayName("Should update ticket status when present and ignore missing ticket cleanly")
    void shouldUpdateTicketStatusOrNoOpWhenMissing() {
        stubTransactionCommit();
        UUID presentId = UUID.randomUUID();
        UUID missingId = UUID.randomUUID();
        Instant updatedAt = Instant.parse("2026-11-10T10:00:00Z");

        DisruptionTicketEntity existing =
                mapper.toTicketEntity(
                        createTicketEvent(SkillGroup.DISRUPTION_DESK_STANDARD, Map.of()),
                        SkillGroup.DISRUPTION_DESK_STANDARD,
                        new HandoffLifecycleState.Queued(
                                CallId.generate(),
                                presentId,
                                SkillGroup.DISRUPTION_DESK_STANDARD,
                                5,
                                updatedAt));
        when(disruptionTicketRepository.findById(presentId.toString()))
                .thenReturn(Optional.of(existing));
        when(disruptionTicketRepository.findById(missingId.toString()))
                .thenReturn(Optional.empty());

        adapter.updateTicketStatus(presentId, " COMPLETED ", updatedAt);
        adapter.updateTicketStatus(missingId, "COMPLETED", updatedAt);

        verify(disruptionTicketRepository, times(1)).save(ticketCaptor.capture());
        assertThat(ticketCaptor.getValue().status()).isEqualTo("COMPLETED");
        assertThat(ticketCaptor.getValue().updatedAt()).isEqualTo(updatedAt);
    }

    @Test
    @DisplayName("Should upsert call session and append audit log during recordHandoffAuditLog")
    void shouldRecordHandoffAuditLogAndUpdateExistingCallSession() {
        stubTransactionCommit();
        CallId callId = CallId.generate();
        Instant initialTime = Instant.parse("2026-11-10T11:00:00Z");
        Instant occurredAt = Instant.parse("2026-11-10T11:05:00Z");
        OperatorId operatorId = OperatorId.fromString("op-99");
        CallSessionEntity existing =
                new CallSessionEntity(
                        callId.value(),
                        "ZX9QW2",
                        "+15553334444",
                        "QUEUED",
                        initialTime,
                        null,
                        null,
                        null,
                        JsonbDocument.of(Map.of("targetSkillGroup", "DISRUPTION_DESK_GOLD")),
                        initialTime,
                        initialTime);
        when(callSessionRepository.findById(callId.value())).thenReturn(Optional.of(existing));

        adapter.recordHandoffAuditLog(
                callId,
                "OPERATOR_ASSIGNED",
                "QUEUED",
                "ASSIGNED",
                operatorId,
                Map.of("bridgeEndpoint", "/ws/bridge/99"),
                occurredAt);

        verify(callSessionRepository).save(callSessionCaptor.capture());
        verify(operatorHandoffLogRepository).save(auditLogCaptor.capture());
        assertThat(callSessionCaptor.getValue().status()).isEqualTo("ASSIGNED");
        assertThat(callSessionCaptor.getValue().assignedOperatorId()).isEqualTo("op-99");
        assertThat(callSessionCaptor.getValue().metadata().entries())
                .containsEntry("targetSkillGroup", "DISRUPTION_DESK_GOLD");

        OperatorHandoffLogEntity savedAudit = auditLogCaptor.getValue();
        assertThat(savedAudit.id()).isNull();
        assertThat(savedAudit.callId()).isEqualTo(callId.value());
        assertThat(savedAudit.eventType()).isEqualTo("OPERATOR_ASSIGNED");
        assertThat(savedAudit.details().entries()).containsEntry("bridgeEndpoint", "/ws/bridge/99");
    }

    @Test
    @DisplayName("Should create initial call session when audit log precedes ticket save")
    void shouldCreateInitialCallSessionWhenAuditPrecedesTicket() {
        stubTransactionCommit();
        CallId callId = CallId.generate();
        Instant occurredAt = Instant.parse("2026-11-10T11:30:00Z");
        when(callSessionRepository.findById(callId.value())).thenReturn(Optional.empty());

        adapter.recordHandoffAuditLog(
                callId, "CALL_QUEUED", null, "QUEUED", null, null, occurredAt);

        verify(jdbcAggregateOperations).insert(callSessionCaptor.capture());
        verify(operatorHandoffLogRepository).save(auditLogCaptor.capture());
        assertThat(callSessionCaptor.getValue().status()).isEqualTo("QUEUED");
        assertThat(callSessionCaptor.getValue().assignedOperatorId()).isNull();
        assertThat(auditLogCaptor.getValue().details()).isEqualTo(JsonbDocument.empty());
    }

    @Test
    @DisplayName("Should reconstruct TicketFormFilledEvent or return empty when ticket missing")
    void shouldFindTicketByCallIdOrReturnEmpty() {
        CallId missingCallId = CallId.generate();
        when(disruptionTicketRepository.findFirstByCallIdOrderByCreatedAtDesc(
                        missingCallId.value()))
                .thenReturn(Optional.empty());

        assertThat(adapter.findTicketByCallId(missingCallId)).isEmpty();

        TicketFormFilledEvent event =
                createTicketEvent(
                        SkillGroup.DISRUPTION_DESK_GOLD, Map.of("seat", "2A", "tier", "GOLD"));
        DisruptionTicketEntity ticketEntity =
                mapper.toTicketEntity(
                        event,
                        SkillGroup.DISRUPTION_DESK_GOLD,
                        new HandoffLifecycleState.Queued(
                                event.callId(),
                                event.ticketId(),
                                SkillGroup.DISRUPTION_DESK_GOLD,
                                100,
                                event.timestamp()));
        CallSessionEntity sessionEntity =
                mapper.toCallSessionEntity(
                        event,
                        SkillGroup.DISRUPTION_DESK_GOLD,
                        new HandoffLifecycleState.Queued(
                                event.callId(),
                                event.ticketId(),
                                SkillGroup.DISRUPTION_DESK_GOLD,
                                100,
                                event.timestamp()));

        AtomicBoolean ranOnVirtualThread = new AtomicBoolean(false);
        when(disruptionTicketRepository.findFirstByCallIdOrderByCreatedAtDesc(
                        event.callId().value()))
                .thenAnswer(
                        ignored -> {
                            ranOnVirtualThread.set(Thread.currentThread().isVirtual());
                            return Optional.of(ticketEntity);
                        });
        when(callSessionRepository.findById(event.callId().value()))
                .thenReturn(Optional.of(sessionEntity));

        Optional<TicketFormFilledEvent> found = adapter.findTicketByCallId(event.callId());

        assertThat(ranOnVirtualThread).isTrue();
        assertThat(found).isPresent();
        assertThat(found.get().ticketId()).isEqualTo(event.ticketId());
        assertThat(found.get().targetSkillGroup()).isEqualTo("DISRUPTION_DESK_GOLD");
        assertThat(found.get().payload()).containsEntry("seat", "2A").containsEntry("tier", "GOLD");
    }

    @Test
    @DisplayName("Should reject blank or null input arguments immediately")
    void shouldRejectInvalidArguments() {
        assertThatThrownBy(() -> adapter.updateTicketStatus(UUID.randomUUID(), "  ", Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                adapter.recordHandoffAuditLog(
                                        CallId.generate(),
                                        " ",
                                        null,
                                        "QUEUED",
                                        null,
                                        Map.of(),
                                        Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                adapter.recordHandoffAuditLog(
                                        CallId.generate(),
                                        "EVENT",
                                        null,
                                        " ",
                                        null,
                                        Map.of(),
                                        Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private void stubTransactionCommit() {
        when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);
    }

    private TicketFormFilledEvent createTicketEvent(
            SkillGroup skillGroup, Map<String, Object> payload) {
        return createTicketEvent(skillGroup, payload, Instant.parse("2026-11-10T08:00:00Z"));
    }

    private TicketFormFilledEvent createTicketEvent(
            SkillGroup skillGroup, Map<String, Object> payload, Instant timestamp) {
        return Instancio.of(TicketFormFilledEvent.class)
                .set(Select.field(TicketFormFilledEvent::callId), CallId.generate())
                .set(Select.field(TicketFormFilledEvent::ticketId), UUID.randomUUID())
                .set(Select.field(TicketFormFilledEvent::pnr), new Pnr("AB7DF9"))
                .set(
                        Select.field(TicketFormFilledEvent::originalFlightNumber),
                        new FlightNumber("SW-104"))
                .set(Select.field(TicketFormFilledEvent::targetSkillGroup), skillGroup.name())
                .set(Select.field(TicketFormFilledEvent::payload), payload)
                .set(Select.field(TicketFormFilledEvent::timestamp), timestamp)
                .create();
    }
}
