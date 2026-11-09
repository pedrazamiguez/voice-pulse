package com.voicepulse.conversation.infrastructure.kafka.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.TopicConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;

class ConversationKafkaConsumerConfigurationTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withConfiguration(
                            AutoConfigurations.of(
                                    KafkaAutoConfiguration.class,
                                    ConversationKafkaConsumerConfiguration.class,
                                    ConversationKafkaTopicConfiguration.class));

    @Test
    @DisplayName("Kafka consumer, DLQ recoverer, and topic beans are loaded by default")
    void whenKafkaEnabled_beansAreLoadedWithDefaults() {
        contextRunner
                .withPropertyValues("spring.kafka.bootstrap-servers=localhost:9092")
                .run(
                        context -> {
                            assertThat(context).hasSingleBean(ConversationKafkaProperties.class);
                            ConversationKafkaProperties properties =
                                    context.getBean(ConversationKafkaProperties.class);
                            assertThat(properties.consumerGroup())
                                    .isEqualTo(ConversationKafkaProperties.DEFAULT_GROUP_ID);
                            assertThat(properties.concurrency())
                                    .isEqualTo(ConversationKafkaProperties.DEFAULT_CONCURRENCY);
                            assertThat(properties.autoOffsetReset())
                                    .isEqualTo(
                                            ConversationKafkaProperties.DEFAULT_AUTO_OFFSET_RESET);

                            assertThat(context).hasBean("audioStreamConsumerFactory");
                            assertThat(context).hasBean("dlqProducerFactory");
                            assertThat(context).hasBean("dlqKafkaTemplate");
                            assertThat(context).hasBean("dlqRecoverer");
                            assertThat(context).hasBean("kafkaCommonErrorHandler");
                            assertThat(context).hasBean("kafkaListenerContainerFactory");

                            assertThat(context).hasSingleBean(ConsumerFactory.class);
                            assertThat(context).hasSingleBean(KafkaTemplate.class);
                            assertThat(context).hasSingleBean(DeadLetterPublishingRecoverer.class);
                            assertThat(context).hasSingleBean(CommonErrorHandler.class);

                            assertThat(context).hasSingleBean(KafkaTopicProperties.class);
                            assertThat(context).hasBean("telephonyCallsRawTopic");
                            assertThat(context).hasBean("telephonyCallsControlTopic");
                            assertThat(context).hasBean("telephonyCallsDlqTopic");

                            NewTopic rawTopic =
                                    context.getBean("telephonyCallsRawTopic", NewTopic.class);
                            assertThat(rawTopic.name()).isEqualTo("telephony.calls.raw");
                            assertThat(rawTopic.numPartitions()).isEqualTo(12);
                            assertThat(rawTopic.configs())
                                    .containsEntry(TopicConfig.RETENTION_MS_CONFIG, "3600000");

                            NewTopic controlTopic =
                                    context.getBean("telephonyCallsControlTopic", NewTopic.class);
                            assertThat(controlTopic.name()).isEqualTo("telephony.calls.control");
                            assertThat(controlTopic.numPartitions()).isEqualTo(12);

                            NewTopic dlqTopic =
                                    context.getBean("telephonyCallsDlqTopic", NewTopic.class);
                            assertThat(dlqTopic.name()).isEqualTo("telephony.calls.dlq");
                            assertThat(dlqTopic.numPartitions()).isEqualTo(3);
                            assertThat(dlqTopic.configs())
                                    .containsEntry(TopicConfig.RETENTION_MS_CONFIG, "1209600000");
                        });
    }

    @Test
    @DisplayName("Kafka consumer and topic beans are NOT loaded when kafka is disabled")
    void whenKafkaDisabled_beansAreNotLoaded() {
        contextRunner
                .withPropertyValues("voicepulse.conversation.kafka.enabled=false")
                .run(
                        context -> {
                            assertThat(context).doesNotHaveBean("audioStreamConsumerFactory");
                            assertThat(context).doesNotHaveBean("dlqProducerFactory");
                            assertThat(context).doesNotHaveBean("dlqKafkaTemplate");
                            assertThat(context).doesNotHaveBean("dlqRecoverer");
                            assertThat(context).doesNotHaveBean("kafkaCommonErrorHandler");
                            assertThat(context).doesNotHaveBean("telephonyCallsRawTopic");
                            assertThat(context).doesNotHaveBean("telephonyCallsControlTopic");
                            assertThat(context).doesNotHaveBean("telephonyCallsDlqTopic");
                        });
    }

    @Test
    @DisplayName("Custom conversation consumer and topic properties bind successfully")
    void whenCustomPropertiesConfigured_bindsProperly() {
        contextRunner
                .withPropertyValues(
                        "spring.kafka.bootstrap-servers=localhost:9092",
                        "voicepulse.conversation.kafka.consumer-group=custom-workers",
                        "voicepulse.conversation.kafka.concurrency=6",
                        "voicepulse.conversation.kafka.auto-offset-reset=latest",
                        "voicepulse.kafka.topics.dlq.name=telephony.calls.custom-dlq",
                        "voicepulse.kafka.topics.dlq.partitions=5",
                        "voicepulse.kafka.topics.dlq.retention=72h")
                .run(
                        context -> {
                            ConversationKafkaProperties properties =
                                    context.getBean(ConversationKafkaProperties.class);
                            assertThat(properties.consumerGroup()).isEqualTo("custom-workers");
                            assertThat(properties.concurrency()).isEqualTo(6);
                            assertThat(properties.autoOffsetReset()).isEqualTo("latest");

                            NewTopic dlqTopic =
                                    context.getBean("telephonyCallsDlqTopic", NewTopic.class);
                            assertThat(dlqTopic.name()).isEqualTo("telephony.calls.custom-dlq");
                            assertThat(dlqTopic.numPartitions()).isEqualTo(5);
                            assertThat(dlqTopic.configs())
                                    .containsEntry(TopicConfig.RETENTION_MS_CONFIG, "259200000");
                        });
    }
}
