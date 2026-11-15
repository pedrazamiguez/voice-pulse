package com.voicepulse.routing.infrastructure.persistence.repository;

import com.voicepulse.routing.infrastructure.persistence.model.DisruptionTicketEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.ListCrudRepository;

/** Spring Data JDBC repository for {@code disruption_tickets}. */
public interface DisruptionTicketRepository
        extends ListCrudRepository<DisruptionTicketEntity, String> {

    /**
     * Finds the most recently created disruption ticket associated with a given call identifier.
     */
    Optional<DisruptionTicketEntity> findFirstByCallIdOrderByCreatedAtDesc(UUID callId);
}
