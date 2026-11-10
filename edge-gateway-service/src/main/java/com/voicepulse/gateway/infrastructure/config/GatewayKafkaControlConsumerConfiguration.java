package com.voicepulse.gateway.infrastructure.config;

import com.voicepulse.shared.event.CallControlEnvelope;
import com.voicepulse.shared.infrastructure.kafka.serializer.CallControlEnvelopeDeserializer;
import java.util.HashMap;
import java.util.Map;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;

/**
 * Spring Kafka consumer configuration for receiving {@link CallControlEnvelope} signaling events
 * (e.g. barge-in interrupts) on {@code telephony.calls.control}.
 */
@Configuration
@EnableConfigurationProperties({GatewayKafkaProperties.class, KafkaProperties.class})
@RequiredArgsConstructor
public class GatewayKafkaControlConsumerConfiguration {

    private final @NonNull KafkaProperties kafkaProperties;
    private final @NonNull GatewayKafkaProperties gatewayKafkaProperties;

    @Bean
    public ConsumerFactory<String, CallControlEnvelope> callControlConsumerFactory() {
        Map<String, Object> props = new HashMap<>(kafkaProperties.buildConsumerProperties());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, gatewayKafkaProperties.consumerGroup());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                CallControlEnvelopeDeserializer.class);
        return new DefaultKafkaConsumerFactory<>(props);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, CallControlEnvelope>
            callControlKafkaListenerContainerFactory(
                    ConsumerFactory<String, CallControlEnvelope> callControlConsumerFactory) {
        ConcurrentKafkaListenerContainerFactory<String, CallControlEnvelope> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(callControlConsumerFactory);
        return factory;
    }
}
