package com.voicepulse.gateway.infrastructure.config;

import com.voicepulse.shared.event.AudioChunkEnvelope;
import com.voicepulse.shared.infrastructure.kafka.serializer.AudioChunkEnvelopeSerializer;
import java.util.HashMap;
import java.util.Map;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

/**
 * Spring configuration providing typed Kafka producer infrastructure for streaming {@link
 * AudioChunkEnvelope} messages.
 */
@Configuration
@EnableConfigurationProperties({GatewayKafkaProperties.class, KafkaProperties.class})
@ConditionalOnProperty(
        prefix = "voicepulse.gateway",
        name = "audio-publisher",
        havingValue = "kafka",
        matchIfMissing = true)
@RequiredArgsConstructor
public class GatewayKafkaConfiguration {

    private final @NonNull KafkaProperties kafkaProperties;
    private final @NonNull GatewayKafkaProperties gatewayKafkaProperties;

    @Bean
    public ProducerFactory<String, AudioChunkEnvelope> audioChunkProducerFactory() {
        Map<String, Object> props = new HashMap<>(kafkaProperties.buildProducerProperties());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, AudioChunkEnvelopeSerializer.class);
        props.put(ProducerConfig.BUFFER_MEMORY_CONFIG, gatewayKafkaProperties.bufferMemory());
        props.put(
                ProducerConfig.MAX_BLOCK_MS_CONFIG,
                (int) gatewayKafkaProperties.maxBlockTimeout().toMillis());
        return new DefaultKafkaProducerFactory<>(props);
    }

    @Bean
    public KafkaTemplate<String, AudioChunkEnvelope> audioChunkKafkaTemplate(
            ProducerFactory<String, AudioChunkEnvelope> producerFactory) {
        return new KafkaTemplate<>(producerFactory);
    }
}
