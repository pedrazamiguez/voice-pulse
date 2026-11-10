package com.voicepulse.conversation.infrastructure.fallback;

import com.voicepulse.conversation.domain.ports.inbound.ProcessCallControlUseCase;
import com.voicepulse.shared.model.CallId;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Fallback;
import org.springframework.stereotype.Component;

/**
 * Fallback no-op implementation of {@link ProcessCallControlUseCase} (Null Object Pattern).
 *
 * <p>Registered as a Spring {@link Fallback} bean so that it is automatically superseded once a
 * concrete call control orchestrator is registered.
 */
@Slf4j
@Fallback
@Component
public class NoOpProcessCallControlUseCase implements ProcessCallControlUseCase {

    @Override
    public void handleCallStarted(CallId callId, Instant startedAt) {
        log.debug("No-op handleCallStarted: callId={}, startedAt={}", callId, startedAt);
    }

    @Override
    public void handleBargeIn(CallId callId, Instant occurredAt) {
        log.debug("No-op handleBargeIn: callId={}, occurredAt={}", callId, occurredAt);
    }

    @Override
    public void handleHangup(CallId callId) {
        log.debug("No-op handleHangup: callId={}", callId);
    }
}
