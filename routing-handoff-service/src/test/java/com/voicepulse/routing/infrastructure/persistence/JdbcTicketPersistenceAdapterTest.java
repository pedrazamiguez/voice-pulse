package com.voicepulse.routing.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.instancio.Select.field;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.voicepulse.routing.domain.model.HandoffLifecycleState;
import com.voicepulse.routing.domain.model.OperatorId;
import com.voicepulse.routing.domain.model.SkillGroup;
import com.voicepulse.routing.infrastructure.persistence.mapper.TicketPersistenceMapper;
import com.voicepulse.routing.infrastructure.persistence.model.CallSessionRow;
import com.voicepulse.routing.infrastructure.persistence.model.DisruptionTicketRow;
import com.voicepulse.shared.event.TicketFormFilledEvent;
import com.voicepulse.shared.infrastructure.kafka.serializer.KafkaJsonCodecProvider;
import com.voicepulse.shared.model.CallId;
import com.voicepulse.shared.model.FlightNumber;
import com.voicepulse.shared.model.Pnr;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.instancio.Instancio;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

@ExtendWith(MockitoExtension.class)
@DisplayName("JdbcTicketPersistenceAdapter Unit Tests")
class JdbcTicketPersistenceAdapterTest {

    private static final Instant NOW = Instant.parse("2026-11-14T11:45:00Z");

    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private TransactionTemplate transactionTemplate;
    @Mock private TransactionStatus transactionStatus;

    private ExecutorService virtualThreadExecutor;
    private TicketPersistenceMapper mapper;
    private JsonbColumnCodec jsonbCodec;
    private JdbcTicketPersistenceAdapter adapter;

    @BeforeEach
    void setUp() {
        virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
        mapper = Mappers.getMapper(TicketPersistenceMapper.class);
        jsonbCodec = new JsonbColumnCodec(KafkaJsonCodecProvider.objectMapper());
        adapter =
                new JdbcTicketPersistenceAdapter(
                        jdbcTemplate,
                        transactionTemplate,
                        virtualThreadExecutor,
                        mapper,
                        jsonbCodec);

        lenient()
                .doAnswer(
                        invocation -> {
                            Consumer<TransactionStatus> callback = invocation.getArgument(0);
                            callback.accept(transactionStatus);
                            return null;
                        })
                .when(transactionTemplate)
                .executeWithoutResult(any());

        lenient()
                .when(transactionTemplate.execute(any()))
                .thenAnswer(
                        invocation -> {
                            TransactionCallback<?> callback = invocation.getArgument(0);
                            return callback.doInTransaction(transactionStatus);
                        });
    }

    @AfterEach
    void tearDown() {
        virtualThreadExecutor.close();
    }

    private static TicketFormFilledEvent sampleEvent() {
        return Instancio.of(TicketFormFilledEvent.class)
                .set(field(TicketFormFilledEvent::pnr), new Pnr("A8F9X2"))
                .set(field(TicketFormFilledEvent::originalFlightNumber), new FlightNumber("SW-104"))
                .set(field(TicketFormFilledEvent::targetSkillGroup), "DISRUPTION_DESK_GOLD")
                .set(field(TicketFormFilledEvent::payload), Map.of("hotel_rooms", 1))
                .set(field(TicketFormFilledEvent::timestamp), NOW)
                .create();
    }

    @Test
    @DisplayName("Should execute saveTicket JDBC upserts inside a Java 21 Virtual Thread")
    void shouldExecuteSaveTicketOnVirtualThread() {
        TicketFormFilledEvent event = sampleEvent();
        HandoffLifecycleState state =
                new HandoffLifecycleState.Queued(
                        event.callId(), event.ticketId(), SkillGroup.DISRUPTION_DESK_GOLD, 1, NOW);
        AtomicBoolean ranOnVirtualThread = new AtomicBoolean(false);

        when(jdbcTemplate.update(any(String.class), any(Object[].class)))
                .thenAnswer(
                        invocation -> {
                            ranOnVirtualThread.set(Thread.currentThread().isVirtual());
                            return 1;
                        });

        adapter.saveTicket(event, SkillGroup.DISRUPTION_DESK_GOLD, state);

        assertThat(ranOnVirtualThread).isTrue();
        CallSessionRow expectedSession =
                mapper.toCallSessionRow(event, SkillGroup.DISRUPTION_DESK_GOLD, state);
        DisruptionTicketRow expectedTicket =
                mapper.toTicketRow(event, SkillGroup.DISRUPTION_DESK_GOLD, state);
        assertThat(expectedSession.callId()).isEqualTo(event.callId().value());
        assertThat(expectedTicket.ticketId()).isEqualTo(event.ticketId().toString());
    }

    @Test
    @DisplayName("Should execute updateTicketStatus and recordHandoffAuditLog on Virtual Threads")
    void shouldExecuteStatusUpdateAndAuditLogOnVirtualThread() {
        UUID ticketId = UUID.randomUUID();
        CallId callId = CallId.generate();
        AtomicBoolean updateVirtual = new AtomicBoolean(false);
        AtomicBoolean auditVirtual = new AtomicBoolean(false);

        when(jdbcTemplate.update(any(String.class), any(Object[].class)))
                .thenAnswer(
                        invocation -> {
                            updateVirtual.set(Thread.currentThread().isVirtual());
                            auditVirtual.set(Thread.currentThread().isVirtual());
                            return 1;
                        });

        adapter.updateTicketStatus(ticketId, "ASSIGNED", NOW);
        adapter.recordHandoffAuditLog(
                callId,
                "OPERATOR_ASSIGNED",
                "QUEUED",
                "ASSIGNED",
                OperatorId.fromString("op-1"),
                Map.of("reason", "auto-match"),
                NOW);

        assertThat(updateVirtual).isTrue();
        assertThat(auditVirtual).isTrue();
    }

    @Test
    @DisplayName("Should return Optional.empty when findTicketByCallId finds no rows")
    @SuppressWarnings("unchecked")
    void shouldReturnEmptyOptionalWhenNoTicketFound() {
        CallId callId = CallId.generate();
        when(jdbcTemplate.query(any(String.class), any(RowMapper.class), eq(callId.value())))
                .thenReturn(List.of());

        Optional<TicketFormFilledEvent> result = adapter.findTicketByCallId(callId);

        assertThat(result).isEmpty();
        verify(jdbcTemplate).query(any(String.class), any(RowMapper.class), eq(callId.value()));
    }

    @Test
    @DisplayName("Should wrap JDBC DataAccessException in TicketPersistenceException")
    void shouldWrapJdbcExceptionsInTicketPersistenceException() {
        TicketFormFilledEvent event = sampleEvent();
        HandoffLifecycleState state =
                new HandoffLifecycleState.Queued(
                        event.callId(), event.ticketId(), SkillGroup.DISRUPTION_DESK_GOLD, 1, NOW);

        doAnswer(
                        invocation -> {
                            throw new DataAccessResourceFailureException("PostgreSQL unavailable");
                        })
                .when(transactionTemplate)
                .executeWithoutResult(any());

        assertThatThrownBy(() -> adapter.saveTicket(event, SkillGroup.DISRUPTION_DESK_GOLD, state))
                .isInstanceOf(TicketPersistenceException.class)
                .hasMessageContaining("saveTicket")
                .hasCauseInstanceOf(DataAccessResourceFailureException.class);
    }

    @Test
    @DisplayName("Should fail fast on blank status, eventType, or targetState")
    void shouldRejectBlankArguments() {
        UUID ticketId = UUID.randomUUID();
        CallId callId = CallId.generate();

        assertThatThrownBy(() -> adapter.updateTicketStatus(ticketId, "  ", NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("status must not be blank");

        assertThatThrownBy(
                        () ->
                                adapter.recordHandoffAuditLog(
                                        callId, " ", null, "QUEUED", null, Map.of(), NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("eventType must not be blank");

        assertThatThrownBy(
                        () ->
                                adapter.recordHandoffAuditLog(
                                        callId, "TICKET_QUEUED", null, "  ", null, Map.of(), NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("targetState must not be blank");
    }
}
