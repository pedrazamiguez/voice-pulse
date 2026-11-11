package com.voicepulse.conversation.infrastructure.kafka.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voicepulse.shared.event.TicketFormFilledEvent;
import com.voicepulse.shared.infrastructure.kafka.serializer.KafkaJsonCodecProvider;
import com.voicepulse.shared.infrastructure.kafka.serializer.TicketFormFilledEventSerializer;
import java.util.HashMap;
import java.util.Map;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

/**
 * Spring Kafka producer infrastructure configuration for dispatching {@link TicketFormFilledEvent}
 * envelopes to {@code telephony.tickets.intent}.
 */
@Configuration
@EnableConfigurationProperties({KafkaProperties.class, KafkaTopicProperties.class})
@RequiredArgsConstructor
public class ConversationKafkaTicketIntentProducerConfiguration {

    private final @NonNull KafkaProperties kafkaProperties;
    private final @NonNull ObjectProvider<ObjectMapper> objectMapperProvider;

    @Bean
    public ProducerFactory<String, TicketFormFilledEvent> ticketIntentProducerFactory() {
        Map<String, Object> props = new HashMap<>(kafkaProperties.buildProducerProperties());
        ObjectMapper mapper =
                objectMapperProvider.getIfAvailable(KafkaJsonCodecProvider::objectMapper);
        return new DefaultKafkaProducerFactory<>(
                props, new StringSerializer(), new TicketFormFilledEventSerializer(mapper));
    }

    @Bean
    public KafkaTemplate<String, TicketFormFilledEvent> ticketIntentKafkaTemplate(
            ProducerFactory<String, TicketFormFilledEvent> ticketIntentProducerFactory) {
        return new KafkaTemplate<>(ticketIntentProducerFactory);
    }
}
