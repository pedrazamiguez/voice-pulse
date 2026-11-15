package com.voicepulse.routing.infrastructure.persistence;

import com.voicepulse.routing.domain.model.HandoffLifecycleState;
import com.voicepulse.routing.domain.model.OperatorId;
import com.voicepulse.routing.domain.model.SkillGroup;
import com.voicepulse.routing.domain.ports.outbound.TicketPersistencePort;
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
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.jdbc.core.JdbcAggregateOperations;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Secondary persistence adapter implementing {@link TicketPersistencePort} via Spring Data JDBC
 * repositories and {@link JdbcAggregateOperations}.
 *
 * <p>All blocking PostgreSQL database I/O executes off the reactive Netty event loop on {@code
 * jdbcVirtualThreadExecutor}.
 */
@Slf4j
@Component
@ConditionalOnProperty(
        prefix = "voicepulse.routing.persistence",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
@RequiredArgsConstructor
public class SpringDataTicketPersistenceAdapter implements TicketPersistencePort {

    private static final int MAX_UPSERT_ATTEMPTS = 2;
    private static final int MAX_CAUSE_DEPTH = 10;

    private final @NonNull CallSessionRepository callSessionRepository;
    private final @NonNull DisruptionTicketRepository disruptionTicketRepository;
    private final @NonNull OperatorHandoffLogRepository operatorHandoffLogRepository;
    private final @NonNull JdbcAggregateOperations jdbcAggregateOperations;
    private final @NonNull TransactionTemplate transactionTemplate;
    private final @NonNull ExecutorService jdbcVirtualThreadExecutor;
    private final @NonNull TicketPersistenceMapper mapper;

    @Override
    public void saveTicket(
            TicketFormFilledEvent event,
            SkillGroup skillGroup,
            HandoffLifecycleState initialState) {
        Objects.requireNonNull(event, "event must not be null");
        Objects.requireNonNull(skillGroup, "skillGroup must not be null");
        Objects.requireNonNull(initialState, "initialState must not be null");

        CallSessionEntity incomingSession =
                mapper.toCallSessionEntity(event, skillGroup, initialState);
        DisruptionTicketEntity incomingTicket =
                mapper.toTicketEntity(event, skillGroup, initialState);

        executeOnVirtualThread(
                () -> {
                    executeWithUpsertRetry(
                            () ->
                                    transactionTemplate.executeWithoutResult(
                                            tx -> {
                                                upsertCallSession(incomingSession);
                                                upsertDisruptionTicket(incomingTicket);
                                            }));
                    log.info(
                            "Persisted disruption ticket: ticketId={}, callId={}, status={},"
                                    + " skillGroup={}",
                            event.ticketId(),
                            event.callId(),
                            incomingTicket.status(),
                            skillGroup.name());
                    return null;
                });
    }

    @Override
    public void updateTicketStatus(UUID ticketId, String status, Instant updatedAt) {
        Objects.requireNonNull(ticketId, "ticketId must not be null");
        String normalizedStatus = requireNonBlank(status, "status");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");

        executeOnVirtualThread(
                () -> {
                    transactionTemplate.executeWithoutResult(
                            tx ->
                                    disruptionTicketRepository
                                            .findById(ticketId.toString())
                                            .ifPresentOrElse(
                                                    existing -> {
                                                        disruptionTicketRepository.save(
                                                                existing.withStatus(
                                                                        normalizedStatus,
                                                                        updatedAt));
                                                        log.debug(
                                                                "Updated ticket status:"
                                                                        + " ticketId={}, status={}",
                                                                ticketId,
                                                                normalizedStatus);
                                                    },
                                                    () ->
                                                            log.warn(
                                                                    "Ignored ticket status update"
                                                                        + " for missing ticket:"
                                                                        + " ticketId={}, status={}",
                                                                    ticketId,
                                                                    normalizedStatus)));
                    return null;
                });
    }

    @Override
    public void recordHandoffAuditLog(
            CallId callId,
            String eventType,
            String sourceState,
            String targetState,
            OperatorId operatorId,
            Map<String, Object> details,
            Instant occurredAt) {
        Objects.requireNonNull(callId, "callId must not be null");
        String normalizedEventType = requireNonBlank(eventType, "eventType");
        String normalizedTargetState = requireNonBlank(targetState, "targetState");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");

        OperatorHandoffLogEntity auditEntity =
                mapper.toAuditLogEntity(
                        callId,
                        normalizedEventType,
                        sourceState,
                        normalizedTargetState,
                        operatorId,
                        details,
                        occurredAt);
        UUID callUuid = callId.value();
        String operatorIdValue = operatorId != null ? operatorId.value() : null;

        executeOnVirtualThread(
                () -> {
                    executeWithUpsertRetry(
                            () ->
                                    transactionTemplate.executeWithoutResult(
                                            tx -> {
                                                upsertCallSessionForAudit(
                                                        callUuid,
                                                        normalizedTargetState,
                                                        operatorIdValue,
                                                        occurredAt);
                                                operatorHandoffLogRepository.save(auditEntity);
                                            }));
                    log.debug(
                            "Recorded handoff audit log: callId={}, eventType={}, targetState={}",
                            callUuid,
                            normalizedEventType,
                            normalizedTargetState);
                    return null;
                });
    }

    @Override
    public Optional<TicketFormFilledEvent> findTicketByCallId(CallId callId) {
        Objects.requireNonNull(callId, "callId must not be null");
        UUID callUuid = callId.value();

        return executeOnVirtualThread(
                () -> {
                    Optional<DisruptionTicketEntity> ticketOpt =
                            disruptionTicketRepository.findFirstByCallIdOrderByCreatedAtDesc(
                                    callUuid);
                    if (ticketOpt.isEmpty()) {
                        return Optional.empty();
                    }
                    String targetSkillGroup =
                            callSessionRepository
                                    .findById(callUuid)
                                    .map(session -> extractTargetSkillGroup(session.metadata()))
                                    .orElse(null);
                    return Optional.of(
                            mapper.toTicketFormFilledEvent(ticketOpt.get(), targetSkillGroup));
                });
    }

    private void upsertCallSession(CallSessionEntity incoming) {
        callSessionRepository
                .findById(incoming.callId())
                .ifPresentOrElse(
                        existing ->
                                callSessionRepository.save(
                                        CallSessionEntity.mergeForTicketSave(existing, incoming)),
                        () -> jdbcAggregateOperations.insert(incoming));
    }

    private void upsertDisruptionTicket(DisruptionTicketEntity incoming) {
        disruptionTicketRepository
                .findById(incoming.ticketId())
                .ifPresentOrElse(
                        existing ->
                                disruptionTicketRepository.save(
                                        DisruptionTicketEntity.mergePreservingCreatedAt(
                                                incoming, existing.createdAt())),
                        () -> jdbcAggregateOperations.insert(incoming));
    }

    private void upsertCallSessionForAudit(
            UUID callUuid, String targetState, String operatorId, Instant occurredAt) {
        callSessionRepository
                .findById(callUuid)
                .ifPresentOrElse(
                        existing ->
                                callSessionRepository.save(
                                        existing.mergeForAudit(
                                                targetState, operatorId, occurredAt)),
                        () ->
                                jdbcAggregateOperations.insert(
                                        CallSessionEntity.initialForAudit(
                                                callUuid, targetState, operatorId, occurredAt)));
    }

    private void executeWithUpsertRetry(Runnable transactionalOperation) {
        for (int attempt = 1; attempt <= MAX_UPSERT_ATTEMPTS; attempt++) {
            try {
                transactionalOperation.run();
                return;
            } catch (RuntimeException ex) {
                if (attempt < MAX_UPSERT_ATTEMPTS && isDuplicateKeyFailure(ex)) {
                    log.debug(
                            "Concurrent first-insert conflict detected; retrying as update merge",
                            ex);
                    continue;
                }
                throw ex;
            }
        }
    }

    private <T> T executeOnVirtualThread(Supplier<T> action) {
        try {
            return CompletableFuture.supplyAsync(action, jdbcVirtualThreadExecutor).join();
        } catch (CompletionException ex) {
            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
            if (cause instanceof Error err) {
                throw err;
            }
            if (cause instanceof TicketPersistenceException persistenceException) {
                throw persistenceException;
            }
            throw new TicketPersistenceException(
                    "PostgreSQL persistence operation failed: " + cause.getMessage(), cause);
        }
    }

    private static boolean isDuplicateKeyFailure(Throwable error) {
        Throwable current = error;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (current instanceof DuplicateKeyException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static String extractTargetSkillGroup(JsonbDocument metadata) {
        if (metadata == null) {
            return null;
        }
        Object targetSkillGroup = metadata.entries().get("targetSkillGroup");
        return targetSkillGroup instanceof String value && !value.isBlank() ? value : null;
    }

    private static String requireNonBlank(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }
}
