package com.voicepulse.gateway.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.voicepulse.gateway.infrastructure.kafka.KafkaAudioFrameDispatcher;
import com.voicepulse.gateway.infrastructure.kafka.KafkaAudioFramePublisher;
import com.voicepulse.gateway.infrastructure.kafka.KafkaPublisherMetricsTracker;
import java.time.Duration;
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
                                    KafkaAutoConfiguration.class, GatewayKafkaConfiguration.class))
                    .withUserConfiguration(
                            KafkaPublisherMetricsTracker.class,
                            KafkaAudioFrameDispatcher.class,
                            KafkaAudioFramePublisher.class);

    @Test
    @DisplayName(
            "Kafka producer beans and publisher are loaded by default when audio-publisher is"
                    + " kafka")
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

                            assertThat(context).hasBean("audioChunkProducerFactory");
                            assertThat(context).hasBean("audioChunkKafkaTemplate");
                            assertThat(context).hasSingleBean(KafkaAudioFramePublisher.class);
                            assertThat(context).hasSingleBean(KafkaAudioFrameDispatcher.class);
                        });
    }

    @Test
    @DisplayName(
            "Kafka producer beans and publisher are NOT loaded when audio-publisher is loopback")
    void whenAudioPublisherIsLoopback_kafkaBeansAreNotLoaded() {
        contextRunner
                .withPropertyValues("voicepulse.gateway.audio-publisher=loopback")
                .run(
                        context -> {
                            assertThat(context).doesNotHaveBean("audioChunkProducerFactory");
                            assertThat(context).doesNotHaveBean("audioChunkKafkaTemplate");
                            assertThat(context).doesNotHaveBean(KafkaAudioFramePublisher.class);
                            assertThat(context).doesNotHaveBean(KafkaAudioFrameDispatcher.class);
                        });
    }

    @Test
    @DisplayName("Custom topic and send timeout properties bind successfully")
    void whenCustomKafkaPropertiesConfigured_bindsProperly() {
        contextRunner
                .withPropertyValues(
                        "spring.kafka.bootstrap-servers=localhost:9092",
                        "voicepulse.gateway.kafka.topic=telephony.calls.custom",
                        "voicepulse.gateway.kafka.send-timeout=5s")
                .run(
                        context -> {
                            assertThat(context).hasSingleBean(GatewayKafkaProperties.class);
                            GatewayKafkaProperties properties =
                                    context.getBean(GatewayKafkaProperties.class);
                            assertThat(properties.topic()).isEqualTo("telephony.calls.custom");
                            assertThat(properties.sendTimeout()).isEqualTo(Duration.ofSeconds(5));
                        });
    }
}
