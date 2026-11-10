package com.voicepulse.conversation.infrastructure.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import com.voicepulse.conversation.domain.ports.outbound.CallControlDispatcherPort;
import com.voicepulse.conversation.infrastructure.kafka.producer.KafkaCallControlDispatcher;
import com.voicepulse.shared.event.BargeInInterrupt;
import com.voicepulse.shared.event.CallControlEnvelope;
import com.voicepulse.shared.infrastructure.kafka.serializer.CallControlEnvelopeDeserializer;
import com.voicepulse.shared.model.CallId;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.annotation.DirtiesContext;

@SpringBootTest(
        properties = {
            "voicepulse.conversation.kafka.enabled=true",
            "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
            "voicepulse.kafka.topics.raw.name=telephony.calls.raw",
            "voicepulse.kafka.topics.raw.partitions=12",
            "voicepulse.kafka.topics.control.name=telephony.calls.control",
            "voicepulse.kafka.topics.control.partitions=12",
            "voicepulse.kafka.topics.dlq.name=telephony.calls.dlq",
            "voicepulse.kafka.topics.dlq.partitions=3"
        })
@EmbeddedKafka(controlledShutdown = true, topics = "telephony.calls.control", partitions = 12)
@DirtiesContext
@DisplayName("KafkaCallControlDispatcher Kafka Integration Tests")
class KafkaCallControlDispatcherIntegrationTest {

    private static final String CONTROL_TOPIC = "telephony.calls.control";

    @Autowired private EmbeddedKafkaBroker embeddedKafkaBroker;

    @Autowired private CallControlDispatcherPort callControlDispatcher;

    private Consumer<String, CallControlEnvelope> controlConsumer;

    @BeforeEach
    void setUp() {
        Map<String, Object> consumerProps =
                KafkaTestUtils.consumerProps("test-control-group", "true", embeddedKafkaBroker);
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        consumerProps.put(
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                CallControlEnvelopeDeserializer.class);

        DefaultKafkaConsumerFactory<String, CallControlEnvelope> consumerFactory =
                new DefaultKafkaConsumerFactory<>(consumerProps);
        controlConsumer = consumerFactory.createConsumer();
        controlConsumer.subscribe(Collections.singletonList(CONTROL_TOPIC));
    }

    @AfterEach
    void tearDown() {
        if (controlConsumer != null) {
            controlConsumer.close();
        }
    }

    @Test
    @DisplayName(
            "KafkaCallControlDispatcher supersedes fallback bean and publishes BargeInInterrupt"
                    + " partitioned by callId")
    void dispatchesBargeInInterruptWithCallIdPartitionKey() {
        assertThat(callControlDispatcher).isInstanceOf(KafkaCallControlDispatcher.class);

        CallId callId = CallId.generate();
        Instant now = Instant.now();
        BargeInInterrupt interrupt = new BargeInInterrupt(callId, 42L, now, 80L);

        callControlDispatcher.dispatch(interrupt);

        ConsumerRecords<String, CallControlEnvelope> records =
                KafkaTestUtils.getRecords(controlConsumer, Duration.ofSeconds(5));

        assertThat(records).isNotEmpty();

        ConsumerRecord<String, CallControlEnvelope> received = null;
        for (ConsumerRecord<String, CallControlEnvelope> record : records) {
            if (record.key().equals(callId.toString())) {
                received = record;
                break;
            }
        }

        assertThat(received).isNotNull();
        assertThat(received.key()).isEqualTo(callId.toString());
        assertThat(received.value()).isInstanceOf(BargeInInterrupt.class);

        BargeInInterrupt receivedInterrupt = (BargeInInterrupt) received.value();
        assertThat(receivedInterrupt.callId()).isEqualTo(callId);
        assertThat(receivedInterrupt.sequenceNumber()).isEqualTo(42L);
        assertThat(receivedInterrupt.playbackOffsetMs()).isEqualTo(80L);
    }
}
