package com.voicepulse.conversation.infrastructure.kafka.consumer;

import com.voicepulse.shared.event.AudioChunkEnvelope;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

/** Primary inbound adapter consuming audio chunk envelopes from {@code telephony.calls.raw}. */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        prefix = "voicepulse.conversation.kafka",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class KafkaAudioStreamConsumer {

    private final AtomicInteger processedChunksCount = new AtomicInteger(0);

    @KafkaListener(
            id = "audioStreamListener",
            topics = "${voicepulse.kafka.topics.raw.name}",
            groupId = "${voicepulse.conversation.kafka.consumer-group}",
            containerFactory = "kafkaListenerContainerFactory")
    public void onAudioChunk(
            @Payload AudioChunkEnvelope envelope,
            @Header(KafkaHeaders.RECEIVED_KEY) String callId,
            @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
            @Header(KafkaHeaders.OFFSET) long offset) {
        processedChunksCount.incrementAndGet();
        log.debug(
                "Consumed audio chunk: callId={}, seq={}, partition={}, offset={}, totalCount={}",
                callId,
                envelope.sequenceNumber(),
                partition,
                offset,
                processedChunksCount.get());
    }

    public int getProcessedChunksCount() {
        return processedChunksCount.get();
    }
}
