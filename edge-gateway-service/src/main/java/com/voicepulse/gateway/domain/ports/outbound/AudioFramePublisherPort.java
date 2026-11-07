package com.voicepulse.gateway.domain.ports.outbound;

import com.voicepulse.shared.model.CallId;

/**
 * Outbound port for streaming ingested audio frames to Kafka topic telephony.calls.raw.
 *
 * <p>Implementations must ensure messages are partitioned strictly by {@link CallId#toString()} to
 * guarantee in-order consumption across downstream worker nodes.
 */
public interface AudioFramePublisherPort {

    /**
     * Publishes a raw binary audio frame.
     *
     * @param callId call identifier used as Kafka message key
     * @param sequenceNumber monotonic sequence number for temporal ordering
     * @param audioPayload binary PCM audio chunk (16 kHz / 8 kHz)
     */
    void publishAudioFrame(CallId callId, long sequenceNumber, byte[] audioPayload);
}
