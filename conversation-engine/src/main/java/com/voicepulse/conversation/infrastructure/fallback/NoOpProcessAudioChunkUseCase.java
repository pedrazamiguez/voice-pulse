package com.voicepulse.conversation.infrastructure.fallback;

import com.voicepulse.conversation.domain.ports.inbound.ProcessAudioChunkUseCase;
import com.voicepulse.shared.model.AudioChunk;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Fallback;
import org.springframework.stereotype.Component;

/**
 * Fallback no-op implementation of {@link ProcessAudioChunkUseCase} (Null Object Pattern).
 *
 * <p>Registered as a Spring {@link Fallback} bean so that it is automatically superseded once a
 * concrete application service or domain orchestrator is registered.
 */
@Slf4j
@Fallback
@Component
public class NoOpProcessAudioChunkUseCase implements ProcessAudioChunkUseCase {

    @Override
    public void processAudioChunk(AudioChunk chunk) {
        log.debug(
                "No-op ProcessAudioChunkUseCase handling audio chunk: sessionId={}, seq={},"
                        + " bytes={}",
                chunk.sessionId(),
                chunk.sequenceNumber(),
                chunk.pcmData().length);
    }
}
