package com.voicepulse.conversation.infrastructure.kafka.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voicepulse.shared.event.CallControlEnvelope;
import com.voicepulse.shared.infrastructure.kafka.serializer.CallControlEnvelopeSerializer;
import java.util.HashMap;
import java.util.Map;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

/**
 * Spring Kafka producer infrastructure configuration for dispatching {@link CallControlEnvelope}
 * signaling messages to {@code telephony.calls.control}.
 */
@Configuration
@EnableConfigurationProperties({KafkaProperties.class, KafkaTopicProperties.class})
@RequiredArgsConstructor
public class ConversationKafkaControlProducerConfiguration {

    private final @NonNull KafkaProperties kafkaProperties;
    private final @NonNull ObjectMapper objectMapper;

    @Bean
    public ProducerFactory<String, CallControlEnvelope> callControlProducerFactory() {
        Map<String, Object> props = new HashMap<>(kafkaProperties.buildProducerProperties());
        return new DefaultKafkaProducerFactory<>(
                props, new StringSerializer(), new CallControlEnvelopeSerializer(objectMapper));
    }

    @Bean
    public KafkaTemplate<String, CallControlEnvelope> callControlKafkaTemplate(
            ProducerFactory<String, CallControlEnvelope> callControlProducerFactory) {
        return new KafkaTemplate<>(callControlProducerFactory);
    }
}
