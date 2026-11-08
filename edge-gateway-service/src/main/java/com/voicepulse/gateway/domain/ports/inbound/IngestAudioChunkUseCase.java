package com.voicepulse.gateway.domain.ports.inbound;

import com.voicepulse.shared.model.AudioChunk;

/** Driving (inbound) port for ingesting raw telephony audio chunks into the gateway pipeline. */
public interface IngestAudioChunkUseCase {

    /**
     * Ingests an incoming audio chunk, verifies monotonic ordering against the session, and
     * dispatches it to downstream publishers.
     *
     * @param chunk incoming audio chunk
     */
    void ingestAudioChunk(AudioChunk chunk);
}
