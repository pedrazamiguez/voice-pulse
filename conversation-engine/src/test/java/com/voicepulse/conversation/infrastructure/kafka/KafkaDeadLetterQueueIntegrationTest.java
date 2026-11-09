package com.voicepulse.conversation.infrastructure.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.fail;

import com.voicepulse.conversation.infrastructure.kafka.consumer.KafkaAudioStreamConsumer;
import com.voicepulse.shared.event.AudioChunkEnvelope;
import com.voicepulse.shared.infrastructure.kafka.serializer.AudioChunkEnvelopeSerializer;
import com.voicepulse.shared.model.AudioFormat;
import com.voicepulse.shared.model.CallId;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
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
            "voicepulse.conversation.kafka.enabled=true",
            "spring.autoconfigure.exclude=",
            "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
            "voicepulse.kafka.topics.raw.name=telephony.calls.raw",
            "voicepulse.kafka.topics.raw.partitions=12",
            "voicepulse.kafka.topics.control.name=telephony.calls.control",
            "voicepulse.kafka.topics.control.partitions=12",
            "voicepulse.kafka.topics.dlq.name=telephony.calls.dlq",
            "voicepulse.kafka.topics.dlq.partitions=3"
        })
@EmbeddedKafka(controlledShutdown = true)
@DirtiesContext
class KafkaDeadLetterQueueIntegrationTest {

    private static final String RAW_TOPIC = "telephony.calls.raw";
    private static final String CONTROL_TOPIC = "telephony.calls.control";
    private static final String DLQ_TOPIC = "telephony.calls.dlq";

    private final EmbeddedKafkaBroker embeddedKafkaBroker;
    private final KafkaListenerEndpointRegistry registry;
    private final KafkaAudioStreamConsumer audioConsumer;

    private Consumer<String, byte[]> dlqConsumer;
    private KafkaTemplate<String, byte[]> rawByteProducer;
    private KafkaTemplate<String, AudioChunkEnvelope> validEnvelopeProducer;

    KafkaDeadLetterQueueIntegrationTest(
            @Autowired EmbeddedKafkaBroker embeddedKafkaBroker,
            @Autowired KafkaListenerEndpointRegistry registry,
            @Autowired KafkaAudioStreamConsumer audioConsumer) {
        this.embeddedKafkaBroker = embeddedKafkaBroker;
        this.registry = registry;
        this.audioConsumer = audioConsumer;
    }

    @BeforeEach
    void setUp() {
        MessageListenerContainer listenerContainer =
                registry.getListenerContainer("audioStreamListener");
        if (listenerContainer != null) {
            ContainerTestUtils.waitForAssignment(listenerContainer, 12);
        }

        Map<String, Object> consumerProps =
                KafkaTestUtils.consumerProps("dlq-direct-group", "true", embeddedKafkaBroker);
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        DefaultKafkaConsumerFactory<String, byte[]> consumerFactory =
                new DefaultKafkaConsumerFactory<>(
                        consumerProps, new StringDeserializer(), new ByteArrayDeserializer());
        dlqConsumer = consumerFactory.createConsumer();

        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(dlqConsumer.partitionsFor(DLQ_TOPIC)).isNotEmpty());

        List<TopicPartition> dlqPartitions =
                dlqConsumer.partitionsFor(DLQ_TOPIC).stream()
                        .map(p -> new TopicPartition(p.topic(), p.partition()))
                        .toList();
        dlqConsumer.assign(dlqPartitions);
        dlqConsumer.seekToBeginning(dlqPartitions);

        Map<String, Object> producerProps = KafkaTestUtils.producerProps(embeddedKafkaBroker);
        DefaultKafkaProducerFactory<String, byte[]> byteProducerFactory =
                new DefaultKafkaProducerFactory<>(
                        producerProps, new StringSerializer(), new ByteArraySerializer());
        rawByteProducer = new KafkaTemplate<>(byteProducerFactory);

        DefaultKafkaProducerFactory<String, AudioChunkEnvelope> envelopeProducerFactory =
                new DefaultKafkaProducerFactory<>(
                        producerProps, new StringSerializer(), new AudioChunkEnvelopeSerializer());
        validEnvelopeProducer = new KafkaTemplate<>(envelopeProducerFactory);
    }

    @AfterEach
    void tearDown() {
        if (dlqConsumer != null) {
            dlqConsumer.close();
        }
    }

    @Test
    @DisplayName("Kafka topic topology is automatically provisioned on the broker")
    void topicTopology_isProvisionedWithExpectedPartitions() {
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(
                        () -> {
                            assertThat(dlqConsumer.partitionsFor(RAW_TOPIC)).hasSize(12);
                            assertThat(dlqConsumer.partitionsFor(CONTROL_TOPIC)).hasSize(12);
                            assertThat(dlqConsumer.partitionsFor(DLQ_TOPIC)).hasSize(3);
                        });
    }

    @Test
    @DisplayName(
            "Corrupted payload is automatically routed to DLQ with partition key and diagnostic"
                    + " headers preserved")
    void corruptedPayload_isRoutedToDlq_preservingKeyAndHeaders() {
        CallId callId = CallId.generate();
        byte[] corruptedBytes = new byte[] {0x12, 0x34, 0x56, 0x78};

        rawByteProducer.send(new ProducerRecord<>(RAW_TOPIC, callId.toString(), corruptedBytes));
        rawByteProducer.flush();

        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(
                        () -> {
                            ConsumerRecords<String, byte[]> records =
                                    dlqConsumer.poll(Duration.ofMillis(300));
                            for (ConsumerRecord<String, byte[]> record : records) {
                                if (callId.toString().equals(record.key())) {
                                    assertThat(record.value()).isEqualTo(corruptedBytes);

                                    Header originalTopicHeader =
                                            record.headers().lastHeader("kafka_dlt-original-topic");
                                    assertThat(originalTopicHeader).isNotNull();
                                    assertThat(
                                                    new String(
                                                            originalTopicHeader.value(),
                                                            StandardCharsets.UTF_8))
                                            .isEqualTo(RAW_TOPIC);

                                    Header exceptionHeader =
                                            record.headers().lastHeader("kafka_dlt-exception-fqcn");
                                    assertThat(exceptionHeader).isNotNull();
                                    return;
                                }
                            }
                            fail("DLQ record for callId " + callId + " not yet received");
                        });
    }

    @Test
    @DisplayName(
            "Consumer remains operational and ingests valid frames following a corrupted payload")
    void consumerContinuesProcessingValidFrames_afterCorruptedPayload() {
        CallId corruptedCallId = CallId.generate();
        byte[] corruptedBytes = new byte[] {0x00, 0x01, 0x02};
        rawByteProducer.send(
                new ProducerRecord<>(RAW_TOPIC, corruptedCallId.toString(), corruptedBytes));
        rawByteProducer.flush();

        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(
                        () -> {
                            ConsumerRecords<String, byte[]> records =
                                    dlqConsumer.poll(Duration.ofMillis(300));
                            for (ConsumerRecord<String, byte[]> record : records) {
                                if (corruptedCallId.toString().equals(record.key())) {
                                    return;
                                }
                            }
                            fail("DLQ record for corruptedCallId not yet received");
                        });

        int initialCount = audioConsumer.getProcessedChunksCount();
        CallId validCallId = CallId.generate();
        AudioChunkEnvelope validEnvelope =
                new AudioChunkEnvelope(
                        validCallId, 1L, Instant.now(), AudioFormat.PCM_16KHZ_MONO, new byte[320]);

        validEnvelopeProducer.send(
                new ProducerRecord<>(RAW_TOPIC, validCallId.toString(), validEnvelope));
        validEnvelopeProducer.flush();

        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(
                        () -> {
                            assertThat(audioConsumer.getProcessedChunksCount())
                                    .isGreaterThan(initialCount);
                        });
    }
}
