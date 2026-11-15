package com.voicepulse.gateway.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.voicepulse.gateway.domain.model.BridgeAuthorizationStatus;
import com.voicepulse.gateway.domain.ports.inbound.BridgeOperatorAudioUseCase;
import com.voicepulse.gateway.domain.ports.inbound.InterruptPlaybackUseCase;
import com.voicepulse.gateway.infrastructure.kafka.consumer.CallControlSignalingConsumer;
import com.voicepulse.shared.model.CallSessionId;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
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
                                    JacksonAutoConfiguration.class,
                                    KafkaAutoConfiguration.class,
                                    GatewayKafkaControlConsumerConfiguration.class))
                    .withBean(InterruptPlaybackUseCase.class, () -> sessionId -> {})
                    .withBean(
                            BridgeOperatorAudioUseCase.class,
                            () ->
                                    new BridgeOperatorAudioUseCase() {
                                        @Override
                                        public void activateBridge(
                                                CallSessionId sessionId,
                                                String operatorId,
                                                String bridgeEndpoint,
                                                Instant bridgedAt) {}

                                        @Override
                                        public BridgeAuthorizationStatus authorizeOperatorBridge(
                                                CallSessionId sessionId, String operatorId) {
                                            return new BridgeAuthorizationStatus.SessionUnavailable(
                                                    sessionId);
                                        }

                                        @Override
                                        public void relayOperatorAudio(
                                                CallSessionId sessionId,
                                                String operatorId,
                                                byte[] pcmData) {}

                                        @Override
                                        public void handleOperatorDisconnected(
                                                CallSessionId sessionId, String operatorId) {}
                                    })
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
