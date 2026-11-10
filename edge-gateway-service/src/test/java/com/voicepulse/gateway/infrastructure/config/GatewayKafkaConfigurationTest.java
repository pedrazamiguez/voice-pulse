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
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class GatewayKafkaConfigurationTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withInitializer(new ConfigDataApplicationContextInitializer())
                    .withConfiguration(
                            AutoConfigurations.of(
                                    KafkaAutoConfiguration.class, GatewayKafkaConfiguration.class))
                    .withUserConfiguration(
                            KafkaPublisherMetricsTracker.class,
                            KafkaAudioFrameDispatcher.class,
                            KafkaAudioFramePublisher.class);

    @Test
    @DisplayName(
            "Kafka producer beans and publisher are loaded from application.yml when"
                    + " audio-publisher is kafka")
    void whenAudioPublisherIsKafka_beansAreLoadedFromApplicationYml() {
        contextRunner.run(
                context -> {
                    assertThat(context).hasSingleBean(GatewayKafkaProperties.class);
                    GatewayKafkaProperties properties =
                            context.getBean(GatewayKafkaProperties.class);
                    assertThat(properties.topic()).isEqualTo("telephony.calls.raw");
                    assertThat(properties.sendTimeout()).isEqualTo(Duration.ofSeconds(3));
                    assertThat(properties.bufferMemory()).isEqualTo(33554432L);
                    assertThat(properties.maxBlockTimeout()).isEqualTo(Duration.ofSeconds(3));

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
    @DisplayName("Custom producer backpressure properties override defaults cleanly")
    void whenCustomKafkaPropertiesConfigured_overridesDefaults() {
        contextRunner
                .withPropertyValues(
                        "voicepulse.gateway.kafka.topic=telephony.calls.custom",
                        "voicepulse.gateway.kafka.send-timeout=5s",
                        "voicepulse.gateway.kafka.buffer-memory=16777216",
                        "voicepulse.gateway.kafka.max-block-timeout=2s")
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
                        });
    }
}
