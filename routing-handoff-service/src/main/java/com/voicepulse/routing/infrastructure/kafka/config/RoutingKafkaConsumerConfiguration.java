package com.voicepulse.routing.infrastructure.kafka.config;

import com.voicepulse.routing.infrastructure.config.RoutingProperties;
import com.voicepulse.shared.infrastructure.kafka.serializer.TicketFormFilledEventDeserializer;
import java.util.HashMap;
import java.util.Map;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.RecordDeserializationException;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.messaging.converter.MessageConversionException;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Spring Kafka consumer and Dead-Letter Queue (DLQ) recovery configuration for consuming {@code
 * TicketFormFilledEvent} envelopes from {@code telephony.tickets.intent}.
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
public class RoutingKafkaConsumerConfiguration {

    private final @NonNull KafkaProperties kafkaProperties;
    private final @NonNull RoutingKafkaProperties routingKafkaProperties;
    private final @NonNull RoutingProperties routingProperties;

    @Bean
    public ConsumerFactory<String, Object> routingTicketIntentConsumerFactory() {
        Map<String, Object> props = new HashMap<>(kafkaProperties.buildConsumerProperties());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, routingProperties.consumerGroup());
        props.put(
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, routingKafkaProperties.autoOffsetReset());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        props.put(
                ErrorHandlingDeserializer.KEY_DESERIALIZER_CLASS,
                StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        props.put(
                ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS,
                TicketFormFilledEventDeserializer.class.getName());
        return new DefaultKafkaConsumerFactory<>(props);
    }

    @Bean
    public ProducerFactory<Object, Object> routingDlqProducerFactory() {
        Map<String, Object> props = new HashMap<>(kafkaProperties.buildProducerProperties());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        return new DefaultKafkaProducerFactory<>(props);
    }

    @Bean
    public KafkaTemplate<Object, Object> routingDlqKafkaTemplate(
            ProducerFactory<Object, Object> routingDlqProducerFactory) {
        return new KafkaTemplate<>(routingDlqProducerFactory);
    }

    @Bean
    public DeadLetterPublishingRecoverer routingDlqRecoverer(
            KafkaTemplate<Object, Object> routingDlqKafkaTemplate) {
        return new DeadLetterPublishingRecoverer(
                routingDlqKafkaTemplate,
                (record, exception) -> new TopicPartition(routingKafkaProperties.dlqTopic(), -1));
    }

    @Bean
    public CommonErrorHandler routingKafkaCommonErrorHandler(
            DeadLetterPublishingRecoverer routingDlqRecoverer) {
        DefaultErrorHandler errorHandler =
                new DefaultErrorHandler(routingDlqRecoverer, new FixedBackOff(0L, 0L));
        errorHandler.addNotRetryableExceptions(
                DeserializationException.class,
                MessageConversionException.class,
                RecordDeserializationException.class);
        errorHandler.setCommitRecovered(true);
        return errorHandler;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Object>
            routingTicketIntentKafkaListenerContainerFactory(
                    ConsumerFactory<String, Object> routingTicketIntentConsumerFactory,
                    CommonErrorHandler routingKafkaCommonErrorHandler) {
        ConcurrentKafkaListenerContainerFactory<String, Object> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(routingTicketIntentConsumerFactory);
        factory.setCommonErrorHandler(routingKafkaCommonErrorHandler);
        factory.setConcurrency(routingKafkaProperties.concurrency());
        return factory;
    }
}
