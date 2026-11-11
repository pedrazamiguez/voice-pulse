package com.voicepulse.conversation.infrastructure.kafka.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.voicepulse.conversation.infrastructure.kafka.worker.PinnedAudioWorkerPool;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.TopicConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;

class ConversationKafkaConsumerConfigurationTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withInitializer(new ConfigDataApplicationContextInitializer())
                    .withPropertyValues("spring.kafka.admin.auto-create=false")
                    .withConfiguration(
                            AutoConfigurations.of(
                                    KafkaAutoConfiguration.class,
                                    ConversationKafkaConsumerConfiguration.class,
                                    ConversationKafkaTopicConfiguration.class));

    @Test
    @DisplayName("Kafka consumer, DLQ recoverer, and topic beans are loaded from application.yml")
    void whenKafkaEnabled_beansAreLoadedFromApplicationYml() {
        contextRunner.run(
                context -> {
                    assertThat(context).hasSingleBean(ConversationKafkaProperties.class);
                    ConversationKafkaProperties properties =
                            context.getBean(ConversationKafkaProperties.class);
                    assertThat(properties.consumerGroup()).isEqualTo("conversation-engine-workers");
                    assertThat(properties.concurrency()).isEqualTo(3);
                    assertThat(properties.autoOffsetReset()).isEqualTo("earliest");
                    assertThat(properties.workerPoolSize()).isEqualTo(8);

                    assertThat(context).hasBean("audioStreamConsumerFactory");
                    assertThat(context).hasBean("dlqProducerFactory");
                    assertThat(context).hasBean("dlqKafkaTemplate");
                    assertThat(context).hasBean("dlqRecoverer");
                    assertThat(context).hasBean("kafkaCommonErrorHandler");
                    assertThat(context).hasBean("kafkaListenerContainerFactory");
                    assertThat(context).hasBean("pinnedAudioWorkerPool");

                    assertThat(context).hasSingleBean(ConsumerFactory.class);
                    assertThat(context).hasSingleBean(KafkaTemplate.class);
                    assertThat(context).hasSingleBean(DeadLetterPublishingRecoverer.class);
                    assertThat(context).hasSingleBean(CommonErrorHandler.class);
                    assertThat(context).hasSingleBean(PinnedAudioWorkerPool.class);

                    assertThat(context).hasSingleBean(KafkaTopicProperties.class);
                    assertThat(context).hasBean("telephonyCallsRawTopic");
                    assertThat(context).hasBean("telephonyCallsControlTopic");
                    assertThat(context).hasBean("telephonyTicketsIntentTopic");
                    assertThat(context).hasBean("telephonyCallsDlqTopic");

                    NewTopic rawTopic = context.getBean("telephonyCallsRawTopic", NewTopic.class);
                    assertThat(rawTopic.name()).isEqualTo("telephony.calls.raw");
                    assertThat(rawTopic.numPartitions()).isEqualTo(12);
                    assertThat(rawTopic.configs())
                            .containsEntry(TopicConfig.RETENTION_MS_CONFIG, "3600000");

                    NewTopic controlTopic =
                            context.getBean("telephonyCallsControlTopic", NewTopic.class);
                    assertThat(controlTopic.name()).isEqualTo("telephony.calls.control");
                    assertThat(controlTopic.numPartitions()).isEqualTo(12);

                    NewTopic ticketsIntentTopic =
                            context.getBean("telephonyTicketsIntentTopic", NewTopic.class);
                    assertThat(ticketsIntentTopic.name()).isEqualTo("telephony.tickets.intent");
                    assertThat(ticketsIntentTopic.numPartitions()).isEqualTo(6);
                    assertThat(ticketsIntentTopic.configs())
                            .containsEntry(TopicConfig.RETENTION_MS_CONFIG, "604800000");

                    NewTopic dlqTopic = context.getBean("telephonyCallsDlqTopic", NewTopic.class);
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
                            assertThat(context).doesNotHaveBean("pinnedAudioWorkerPool");
                            assertThat(context).doesNotHaveBean("telephonyCallsRawTopic");
                            assertThat(context).doesNotHaveBean("telephonyCallsControlTopic");
                            assertThat(context).doesNotHaveBean("telephonyTicketsIntentTopic");
                            assertThat(context).doesNotHaveBean("telephonyCallsDlqTopic");
                        });
    }

    @Test
    @DisplayName("Custom properties override defaults cleanly")
    void whenCustomPropertiesConfigured_overridesDefaults() {
        contextRunner
                .withPropertyValues(
                        "voicepulse.conversation.kafka.consumer-group=custom-workers",
                        "voicepulse.conversation.kafka.concurrency=6",
                        "voicepulse.conversation.kafka.auto-offset-reset=latest",
                        "voicepulse.conversation.kafka.worker-pool-size=16",
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
                            assertThat(properties.workerPoolSize()).isEqualTo(16);

                            NewTopic dlqTopic =
                                    context.getBean("telephonyCallsDlqTopic", NewTopic.class);
                            assertThat(dlqTopic.name()).isEqualTo("telephony.calls.custom-dlq");
                            assertThat(dlqTopic.numPartitions()).isEqualTo(5);
                            assertThat(dlqTopic.configs())
                                    .containsEntry(TopicConfig.RETENTION_MS_CONFIG, "259200000");
                        });
    }

    @Test
    @DisplayName("Invalid non-positive worker pool size fails fast with IllegalArgumentException")
    void whenInvalidWorkerPoolSize_failsFast() {
        assertThatThrownBy(() -> new ConversationKafkaProperties(true, "grp", 3, "earliest", 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("workerPoolSize must be positive");

        assertThatThrownBy(() -> new ConversationKafkaProperties(true, "grp", 3, "earliest", -5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("workerPoolSize must be positive");
    }
}
