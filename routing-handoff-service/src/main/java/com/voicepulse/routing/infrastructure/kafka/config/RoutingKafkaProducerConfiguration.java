package com.voicepulse.routing.infrastructure.kafka.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voicepulse.routing.infrastructure.config.RoutingProperties;
import com.voicepulse.shared.event.CallControlEnvelope;
import com.voicepulse.shared.infrastructure.kafka.serializer.CallControlEnvelopeSerializer;
import com.voicepulse.shared.infrastructure.kafka.serializer.KafkaJsonCodecProvider;
import java.util.HashMap;
import java.util.Map;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

/**
 * Spring Kafka idempotent producer infrastructure configuration for publishing {@link
 * CallControlEnvelope} reroute signals to {@code telephony.calls.control}.
 */
@Configuration
@EnableConfigurationProperties({
    RoutingKafkaProperties.class,
    RoutingProperties.class,
    KafkaProperties.class
})
@ConditionalOnProperty(
        prefix = "voicepulse.routing.kafka",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
@RequiredArgsConstructor
public class RoutingKafkaProducerConfiguration {

    private final @NonNull KafkaProperties kafkaProperties;
    private final @NonNull ObjectProvider<ObjectMapper> objectMapperProvider;

    @Bean
    public ProducerFactory<String, CallControlEnvelope> routingCallControlProducerFactory() {
        Map<String, Object> props = new HashMap<>(kafkaProperties.buildProducerProperties());
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        ObjectMapper mapper =
                objectMapperProvider.getIfAvailable(KafkaJsonCodecProvider::objectMapper);
        return new DefaultKafkaProducerFactory<>(
                props, new StringSerializer(), new CallControlEnvelopeSerializer(mapper));
    }

    @Bean
    public KafkaTemplate<String, CallControlEnvelope> routingCallControlKafkaTemplate(
            ProducerFactory<String, CallControlEnvelope> routingCallControlProducerFactory) {
        return new KafkaTemplate<>(routingCallControlProducerFactory);
    }
}
