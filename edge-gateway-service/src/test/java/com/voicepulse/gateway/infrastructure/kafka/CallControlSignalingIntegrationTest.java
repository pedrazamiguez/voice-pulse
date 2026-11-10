package com.voicepulse.gateway.infrastructure.kafka;

import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.verify;

import com.voicepulse.gateway.domain.model.CallSession;
import com.voicepulse.gateway.domain.ports.outbound.AudioPlaybackControlPort;
import com.voicepulse.gateway.domain.ports.outbound.SessionRegistryPort;
import com.voicepulse.shared.event.BargeInInterrupt;
import com.voicepulse.shared.event.CallControlEnvelope;
import com.voicepulse.shared.infrastructure.kafka.serializer.CallControlEnvelopeSerializer;
import com.voicepulse.shared.model.CallId;
import com.voicepulse.shared.model.CallSessionId;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.ContainerTestUtils;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.annotation.DirtiesContext;

@SpringBootTest(
        properties = {
            "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
            "spring.kafka.consumer.auto-offset-reset=earliest",
            "voicepulse.gateway.audio-publisher=loopback",
            "voicepulse.gateway.kafka.control-topic=telephony.calls.control",
            "voicepulse.gateway.kafka.consumer-group=edge-gateway-control-test-group"
        })
@EmbeddedKafka(controlledShutdown = true, topics = "telephony.calls.control", partitions = 1)
@DirtiesContext
@DisplayName("CallControlSignaling Consumer Kafka Integration Tests")
class CallControlSignalingIntegrationTest {

    private static final String CONTROL_TOPIC = "telephony.calls.control";

    @Autowired private EmbeddedKafkaBroker embeddedKafkaBroker;

    @Autowired private KafkaListenerEndpointRegistry kafkaListenerEndpointRegistry;

    @Autowired private SessionRegistryPort sessionRegistry;

    @MockBean private AudioPlaybackControlPort audioPlaybackControlPort;

    private KafkaTemplate<String, CallControlEnvelope> controlTemplate;

    @BeforeEach
    void setUp() {
        for (MessageListenerContainer container :
                kafkaListenerEndpointRegistry.getListenerContainers()) {
            ContainerTestUtils.waitForAssignment(container, 1);
        }

        Map<String, Object> producerProps = KafkaTestUtils.producerProps(embeddedKafkaBroker);
        producerProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        producerProps.put(
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, CallControlEnvelopeSerializer.class);

        DefaultKafkaProducerFactory<String, CallControlEnvelope> producerFactory =
                new DefaultKafkaProducerFactory<>(producerProps);
        controlTemplate = new KafkaTemplate<>(producerFactory);
    }

    @AfterEach
    void tearDown() {
        if (controlTemplate != null) {
            controlTemplate.destroy();
        }
    }

    @Test
    @DisplayName(
            "Kafka listener consumes BargeInInterrupt from telephony.calls.control and invokes"
                    + " AudioPlaybackControlPort")
    void consumesBargeInInterruptAndInvokesUseCase() {
        CallId callId = CallId.generate();
        CallSessionId sessionId = CallSessionId.fromCallId(callId);
        sessionRegistry.save(CallSession.start(sessionId, Instant.now()));

        BargeInInterrupt interrupt = new BargeInInterrupt(callId, 15L, Instant.now(), 60L);

        controlTemplate.send(CONTROL_TOPIC, callId.toString(), interrupt);

        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> verify(audioPlaybackControlPort).interruptPlayback(sessionId));
    }
}
