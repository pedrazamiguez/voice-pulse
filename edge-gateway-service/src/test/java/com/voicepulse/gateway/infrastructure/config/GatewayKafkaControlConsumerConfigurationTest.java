package com.voicepulse.gateway.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.voicepulse.gateway.domain.ports.inbound.InterruptPlaybackUseCase;
import com.voicepulse.gateway.infrastructure.kafka.consumer.CallControlSignalingConsumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

@DisplayName("GatewayKafkaControlConsumerConfiguration Tests")
class GatewayKafkaControlConsumerConfigurationTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withInitializer(new ConfigDataApplicationContextInitializer())
                    .withConfiguration(
                            AutoConfigurations.of(
                                    KafkaAutoConfiguration.class,
                                    GatewayKafkaControlConsumerConfiguration.class))
                    .withBean(InterruptPlaybackUseCase.class, () -> sessionId -> {})
                    .withUserConfiguration(CallControlSignalingConsumer.class);

    @Test
    @DisplayName(
            "Kafka control consumer factory, listener container factory, and consumer bean are"
                    + " loaded")
    void controlConsumerBeansLoaded() {
        contextRunner.run(
                context -> {
                    assertThat(context).hasBean("callControlConsumerFactory");
                    assertThat(context).hasBean("callControlKafkaListenerContainerFactory");
                    assertThat(context).hasSingleBean(CallControlSignalingConsumer.class);

                    GatewayKafkaProperties props = context.getBean(GatewayKafkaProperties.class);
                    assertThat(props.controlTopic()).isEqualTo("telephony.calls.control");
                    assertThat(props.consumerGroup()).isEqualTo("edge-gateway-control-workers");
                });
    }
}
