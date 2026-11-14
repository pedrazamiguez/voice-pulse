package com.voicepulse.routing.infrastructure.fallback;

import com.voicepulse.routing.domain.ports.outbound.AudioRerouteSignalPort;
import com.voicepulse.shared.event.OperatorRerouteSignal;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Fallback;
import org.springframework.stereotype.Component;

/** Fallback no-op implementation of {@link AudioRerouteSignalPort} (Null Object Pattern). */
@Slf4j
@Fallback
@Component
public class NoOpAudioRerouteSignalPort implements AudioRerouteSignalPort {

    @Override
    public void sendRerouteSignal(OperatorRerouteSignal signal) {
        log.debug(
                "No-op sendRerouteSignal: callId={}, operatorId={}, bridgeEndpoint={}",
                signal.callId(),
                signal.operatorId(),
                signal.bridgeEndpoint());
    }
}
