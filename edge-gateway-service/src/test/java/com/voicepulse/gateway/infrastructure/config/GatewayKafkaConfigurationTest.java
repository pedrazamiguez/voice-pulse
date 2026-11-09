package com.voicepulse.gateway.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.voicepulse.gateway.infrastructure.kafka.KafkaAudioFrameDispatcher;
import com.voicepulse.gateway.infrastructure.kafka.KafkaAudioFramePublisher;
import com.voicepulse.gateway.infrastructure.kafka.KafkaPublisherMetricsTracker;
import java.time.Duration;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.TopicConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class GatewayKafkaConfigurationTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withConfiguration(
                            AutoConfigurations.of(
                                    KafkaAutoConfiguration.class,
                                    GatewayKafkaConfiguration.class,
                                    GatewayKafkaTopicConfiguration.class))
                    .withUserConfiguration(
                            KafkaPublisherMetricsTracker.class,
                            KafkaAudioFrameDispatcher.class,
                            KafkaAudioFramePublisher.class);

    @Test
    @DisplayName(
            "Kafka producer beans, topic beans, and publisher are loaded by default when"
                    + " audio-publisher is kafka")
    void whenAudioPublisherIsKafka_beansAreLoadedWithDefaults() {
        contextRunner
                .withPropertyValues("spring.kafka.bootstrap-servers=localhost:9092")
                .run(
                        context -> {
                            assertThat(context).hasSingleBean(GatewayKafkaProperties.class);
                            GatewayKafkaProperties properties =
                                    context.getBean(GatewayKafkaProperties.class);
                            assertThat(properties.topic())
                                    .isEqualTo(GatewayKafkaProperties.DEFAULT_TOPIC);
                            assertThat(properties.sendTimeout())
                                    .isEqualTo(GatewayKafkaProperties.DEFAULT_TIMEOUT);
                            assertThat(properties.bufferMemory())
                                    .isEqualTo(GatewayKafkaProperties.DEFAULT_BUFFER_MEMORY);
                            assertThat(properties.maxBlockTimeout())
                                    .isEqualTo(GatewayKafkaProperties.DEFAULT_MAX_BLOCK_TIMEOUT);

                            assertThat(context).hasBean("audioChunkProducerFactory");
                            assertThat(context).hasBean("audioChunkKafkaTemplate");
                            assertThat(context).hasSingleBean(KafkaAudioFramePublisher.class);
                            assertThat(context).hasSingleBean(KafkaAudioFrameDispatcher.class);

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
    @DisplayName(
            "Kafka producer beans and topic provisioning are NOT loaded when audio-publisher is"
                    + " loopback")
    void whenAudioPublisherIsLoopback_kafkaBeansAreNotLoaded() {
        contextRunner
                .withPropertyValues("voicepulse.gateway.audio-publisher=loopback")
                .run(
                        context -> {
                            assertThat(context).doesNotHaveBean("audioChunkProducerFactory");
                            assertThat(context).doesNotHaveBean("audioChunkKafkaTemplate");
                            assertThat(context).doesNotHaveBean(KafkaAudioFramePublisher.class);
                            assertThat(context).doesNotHaveBean(KafkaAudioFrameDispatcher.class);
                            assertThat(context).doesNotHaveBean("telephonyCallsRawTopic");
                            assertThat(context).doesNotHaveBean("telephonyCallsControlTopic");
                            assertThat(context).doesNotHaveBean("telephonyCallsDlqTopic");
                        });
    }

    @Test
    @DisplayName("Custom producer backpressure and topic properties bind successfully")
    void whenCustomKafkaPropertiesConfigured_bindsProperly() {
        contextRunner
                .withPropertyValues(
                        "spring.kafka.bootstrap-servers=localhost:9092",
                        "voicepulse.gateway.kafka.topic=telephony.calls.custom",
                        "voicepulse.gateway.kafka.send-timeout=5s",
                        "voicepulse.gateway.kafka.buffer-memory=16777216",
                        "voicepulse.gateway.kafka.max-block-timeout=2s",
                        "voicepulse.kafka.topics.raw.name=telephony.calls.custom-raw",
                        "voicepulse.kafka.topics.raw.partitions=6",
                        "voicepulse.kafka.topics.raw.retention=30m",
                        "voicepulse.kafka.topics.raw.segment-bytes=10485760")
                .run(
                        context -> {
                            assertThat(context).hasSingleBean(GatewayKafkaProperties.class);
                            GatewayKafkaProperties properties =
                                    context.getBean(GatewayKafkaProperties.class);
                            assertThat(properties.topic()).isEqualTo("telephony.calls.custom");
                            assertThat(properties.sendTimeout()).isEqualTo(Duration.ofSeconds(5));
                            assertThat(properties.bufferMemory()).isEqualTo(16777216L);
                            assertThat(properties.maxBlockTimeout())
                                    .isEqualTo(Duration.ofSeconds(2));

                            NewTopic rawTopic =
                                    context.getBean("telephonyCallsRawTopic", NewTopic.class);
                            assertThat(rawTopic.name()).isEqualTo("telephony.calls.custom-raw");
                            assertThat(rawTopic.numPartitions()).isEqualTo(6);
                            assertThat(rawTopic.configs())
                                    .containsEntry(TopicConfig.RETENTION_MS_CONFIG, "1800000");
                            assertThat(rawTopic.configs())
                                    .containsEntry(TopicConfig.SEGMENT_BYTES_CONFIG, "10485760");
                        });
    }
}
