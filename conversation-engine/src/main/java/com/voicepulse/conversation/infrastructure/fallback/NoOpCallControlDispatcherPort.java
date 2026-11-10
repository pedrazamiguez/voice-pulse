package com.voicepulse.conversation.infrastructure.fallback;

import com.voicepulse.conversation.domain.ports.outbound.CallControlDispatcherPort;
import com.voicepulse.shared.event.CallControlEnvelope;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Fallback;
import org.springframework.stereotype.Component;

/**
 * Fallback no-op implementation of {@link CallControlDispatcherPort} (Null Object Pattern).
 *
 * <p>Registered as a Spring {@link Fallback} bean so that it is automatically superseded once a
 * concrete Kafka control signaling publisher adapter is registered.
 */
@Slf4j
@Fallback
@Component
public class NoOpCallControlDispatcherPort implements CallControlDispatcherPort {

    @Override
    public void dispatch(CallControlEnvelope envelope) {
        log.debug(
                "No-op CallControlDispatcherPort dispatching: callId={}, seq={}, event={}",
                envelope.callId(),
                envelope.sequenceNumber(),
                envelope.getClass().getSimpleName());
    }
}
