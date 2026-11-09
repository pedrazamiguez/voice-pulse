package com.voicepulse.conversation.domain.ports.inbound;

import com.voicepulse.shared.model.AudioChunk;

/**
 * Driving (inbound) port for processing raw incoming telephony audio chunks in the conversation
 * engine.
 */
public interface ProcessAudioChunkUseCase {

    /**
     * Processes an in-order audio chunk for conversational intelligence and VAD evaluation.
     *
     * @param chunk monotonic in-order audio chunk
     */
    void processAudioChunk(AudioChunk chunk);
}
