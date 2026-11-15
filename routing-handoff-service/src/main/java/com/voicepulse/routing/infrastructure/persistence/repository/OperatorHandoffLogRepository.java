package com.voicepulse.routing.infrastructure.persistence.repository;

import com.voicepulse.routing.infrastructure.persistence.model.OperatorHandoffLogEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.repository.ListCrudRepository;

/** Spring Data JDBC repository for {@code operator_handoff_logs}. */
public interface OperatorHandoffLogRepository
        extends ListCrudRepository<OperatorHandoffLogEntity, Long> {

    /** Returns all audit log entries for a call session ordered chronologically by sequence ID. */
    List<OperatorHandoffLogEntity> findByCallIdOrderByIdAsc(UUID callId);
}
