package com.voicepulse.routing.infrastructure.persistence.repository;

import com.voicepulse.routing.infrastructure.persistence.model.CallSessionEntity;
import java.util.UUID;
import org.springframework.data.repository.ListCrudRepository;

/** Spring Data JDBC repository for {@code call_sessions}. */
public interface CallSessionRepository extends ListCrudRepository<CallSessionEntity, UUID> {}
