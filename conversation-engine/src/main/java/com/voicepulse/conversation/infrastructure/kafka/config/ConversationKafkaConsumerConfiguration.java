package com.voicepulse.conversation.infrastructure.kafka.config;

import com.voicepulse.conversation.infrastructure.kafka.worker.PinnedAudioWorkerPool;
import com.voicepulse.shared.infrastructure.kafka.serializer.AudioChunkEnvelopeDeserializer;
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
 * Spring Kafka consumer and Dead-Letter Queue (DLQ) error recovery infrastructure configuration for
 * Conversation Engine.
 */
@Configuration
@EnableConfigurationProperties({
    ConversationKafkaProperties.class,
    KafkaTopicProperties.class,
    KafkaProperties.class
})
@ConditionalOnProperty(
        prefix = "voicepulse.conversation.kafka",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
@RequiredArgsConstructor
public class ConversationKafkaConsumerConfiguration {

    private final @NonNull KafkaProperties kafkaProperties;
    private final @NonNull ConversationKafkaProperties conversationProperties;
    private final @NonNull KafkaTopicProperties topicProperties;

    @Bean
    public ConsumerFactory<String, Object> audioStreamConsumerFactory() {
        Map<String, Object> props = new HashMap<>(kafkaProperties.buildConsumerProperties());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, conversationProperties.consumerGroup());
        props.put(
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, conversationProperties.autoOffsetReset());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        props.put(
                ErrorHandlingDeserializer.KEY_DESERIALIZER_CLASS,
                StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        props.put(
                ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS,
                AudioChunkEnvelopeDeserializer.class.getName());
        return new DefaultKafkaConsumerFactory<>(props);
    }

    @Bean
    public ProducerFactory<Object, Object> dlqProducerFactory() {
        Map<String, Object> props = new HashMap<>(kafkaProperties.buildProducerProperties());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        return new DefaultKafkaProducerFactory<>(props);
    }

    @Bean
    public KafkaTemplate<Object, Object> dlqKafkaTemplate(
            ProducerFactory<Object, Object> dlqProducerFactory) {
        return new KafkaTemplate<>(dlqProducerFactory);
    }

    @Bean
    public DeadLetterPublishingRecoverer dlqRecoverer(
            KafkaTemplate<Object, Object> dlqKafkaTemplate) {
        return new DeadLetterPublishingRecoverer(
                dlqKafkaTemplate,
                (record, exception) -> new TopicPartition(topicProperties.dlq().name(), -1));
    }

    @Bean
    public CommonErrorHandler kafkaCommonErrorHandler(DeadLetterPublishingRecoverer dlqRecoverer) {
        DefaultErrorHandler errorHandler =
                new DefaultErrorHandler(dlqRecoverer, new FixedBackOff(0L, 0L));
        errorHandler.addNotRetryableExceptions(
                DeserializationException.class,
                MessageConversionException.class,
                RecordDeserializationException.class);
        errorHandler.setCommitRecovered(true);
        return errorHandler;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Object> kafkaListenerContainerFactory(
            ConsumerFactory<String, Object> audioStreamConsumerFactory,
            CommonErrorHandler kafkaCommonErrorHandler) {
        ConcurrentKafkaListenerContainerFactory<String, Object> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(audioStreamConsumerFactory);
        factory.setCommonErrorHandler(kafkaCommonErrorHandler);
        factory.setConcurrency(conversationProperties.concurrency());
        return factory;
    }

    @Bean
    public PinnedAudioWorkerPool pinnedAudioWorkerPool() {
        return new PinnedAudioWorkerPool(conversationProperties.workerPoolSize());
    }
}
