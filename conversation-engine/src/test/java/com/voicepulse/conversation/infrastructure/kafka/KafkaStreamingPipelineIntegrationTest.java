package com.voicepulse.conversation.infrastructure.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.voicepulse.conversation.ConversationEngineApplication;
import com.voicepulse.conversation.infrastructure.kafka.consumer.KafkaAudioStreamConsumer;
import com.voicepulse.conversation.infrastructure.kafka.sequence.MonotonicSequenceVerifier;
import com.voicepulse.conversation.infrastructure.kafka.sequence.SequenceVerificationMetrics;
import com.voicepulse.gateway.infrastructure.config.GatewayKafkaConfiguration;
import com.voicepulse.gateway.infrastructure.kafka.KafkaAudioFrameDispatcher;
import com.voicepulse.gateway.infrastructure.kafka.KafkaAudioFramePublisher;
import com.voicepulse.gateway.infrastructure.kafka.KafkaProducerMetrics;
import com.voicepulse.gateway.infrastructure.kafka.KafkaPublisherMetricsTracker;
import com.voicepulse.shared.model.CallId;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.test.utils.ContainerTestUtils;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * End-to-end multi-module integration test verifying reliable high-throughput audio streaming from
 * {@link KafkaAudioFramePublisher} (edge-gateway-service), across Apache Kafka KRaft
 * (Testcontainers), into {@link KafkaAudioStreamConsumer} (conversation-engine).
 */
@SpringBootTest(
        classes = {
            ConversationEngineApplication.class,
            GatewayKafkaConfiguration.class,
            KafkaAudioFramePublisher.class,
            KafkaAudioFrameDispatcher.class,
            KafkaPublisherMetricsTracker.class,
            RecordingProcessAudioChunkUseCase.class
        },
        properties = {
            "spring.main.web-application-type=none",
            "voicepulse.conversation.kafka.enabled=true",
            "voicepulse.gateway.audio-publisher=kafka",
            "voicepulse.gateway.kafka.topic=telephony.calls.raw",
            "voicepulse.gateway.kafka.send-timeout=5s",
            "voicepulse.gateway.kafka.buffer-memory=33554432",
            "voicepulse.gateway.kafka.max-block-timeout=5s",
            "spring.autoconfigure.exclude=",
            "voicepulse.kafka.topics.raw.name=telephony.calls.raw",
            "voicepulse.kafka.topics.raw.partitions=12",
            "voicepulse.kafka.topics.control.name=telephony.calls.control",
            "voicepulse.kafka.topics.control.partitions=12",
            "voicepulse.kafka.topics.dlq.name=telephony.calls.dlq",
            "voicepulse.kafka.topics.dlq.partitions=3",
            "voicepulse.conversation.kafka.consumer-group=e2e-streaming-pipeline-group",
            "voicepulse.conversation.kafka.concurrency=3",
            "voicepulse.conversation.kafka.auto-offset-reset=earliest",
            "voicepulse.conversation.kafka.worker-pool-size=8"
        })
@Testcontainers
@DirtiesContext
class KafkaStreamingPipelineIntegrationTest {

    private static final String CONSUMER_GROUP = "e2e-streaming-pipeline-group";

    @Container
    static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("apache/kafka:3.8.0"));

    private final KafkaAudioFramePublisher audioPublisher;
    private final RecordingProcessAudioChunkUseCase recordingUseCase;
    private final MonotonicSequenceVerifier sequenceVerifier;
    private final KafkaListenerEndpointRegistry registry;

    KafkaStreamingPipelineIntegrationTest(
            @Autowired KafkaAudioFramePublisher audioPublisher,
            @Autowired RecordingProcessAudioChunkUseCase recordingUseCase,
            @Autowired MonotonicSequenceVerifier sequenceVerifier,
            @Autowired KafkaListenerEndpointRegistry registry) {
        this.audioPublisher = audioPublisher;
        this.recordingUseCase = recordingUseCase;
        this.sequenceVerifier = sequenceVerifier;
        this.registry = registry;
    }

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @BeforeEach
    void setUp() {
        MessageListenerContainer listenerContainer =
                registry.getListenerContainer("audioStreamListener");
        if (listenerContainer != null) {
            ContainerTestUtils.waitForAssignment(listenerContainer, 12);
        }
        recordingUseCase.reset();
    }

    @Test
    @DisplayName(
            "Stream 5,000 continuous synthetic 20ms PCM frames with 0% loss, strict monotonic"
                    + " ordering, and <50ms latency")
    void streamContinuousFrames_verifiesFullIntegrityZeroLossAndLowLatency() throws Exception {
        CallId callId = CallId.generate();
        int totalFrames = 5000;

        for (long seq = 0; seq < totalFrames; seq++) {
            byte[] payload = SyntheticAudioStreamGenerator.generatePayload(callId, seq);
            audioPublisher.publishAudioFrame(callId, seq, payload);
            LockSupport.parkNanos(200_000L);
        }

        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(
                        () ->
                                assertThat(recordingUseCase.getChunksFor(callId))
                                        .hasSize(totalFrames));

        // 1. Assert 0% packet loss (exact 5,000 frames)
        assertThat(recordingUseCase.getChunksFor(callId)).hasSize(totalFrames);

        // 2. Assert strictly monotonic sequence (seq = 0..4999)
        assertThat(recordingUseCase.verifyStrictMonotonicSequence(callId, 0L, totalFrames - 1L))
                .isTrue();

        // 3. Assert 100% byte integrity
        assertThat(recordingUseCase.verifyPayloadIntegrity(callId)).isTrue();

        // 4. Assert average transport latency < 50ms
        double avgLatency = recordingUseCase.getAverageLatencyMs(callId);
        assertThat(avgLatency).isLessThan(50.0);

        // 5. Assert single worker thread affinity per session
        assertThat(recordingUseCase.verifySingleThreadExecution(callId)).isTrue();

        // 6. Assert consumer group lag across partitions is 0
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(getConsumerGroupLag(CONSUMER_GROUP)).isZero());

        // 7. Assert sequence verifier metrics
        SequenceVerificationMetrics seqMetrics = sequenceVerifier.getMetrics();
        assertThat(seqMetrics.gapCount()).isZero();
        assertThat(seqMetrics.duplicateCount()).isZero();
        assertThat(seqMetrics.outOfOrderCount()).isZero();
        assertThat(seqMetrics.validFrames()).isGreaterThanOrEqualTo(totalFrames);

        // 8. Assert publisher metrics
        KafkaProducerMetrics pubMetrics = audioPublisher.getMetrics();
        assertThat(pubMetrics.totalPublishedFrames()).isGreaterThanOrEqualTo(totalFrames);
        assertThat(pubMetrics.failedPublishedFrames()).isZero();
        assertThat(pubMetrics.successfulPublishedFrames()).isGreaterThanOrEqualTo(totalFrames);
    }

    @Test
    @DisplayName(
            "Stream concurrent call sessions asserting per-session monotonic sequence"
                    + " preservation and hash-striped thread pinning")
    void streamConcurrentSessions_verifiesPerSessionSequenceAndThreadPinning() {
        int sessionCount = 5;
        int framesPerSession = 200;
        List<CallId> callIds =
                IntStream.range(0, sessionCount).mapToObj(i -> CallId.generate()).toList();

        for (long seq = 0; seq < framesPerSession; seq++) {
            for (CallId callId : callIds) {
                byte[] payload = SyntheticAudioStreamGenerator.generatePayload(callId, seq);
                audioPublisher.publishAudioFrame(callId, seq, payload);
            }
        }

        int expectedTotal = sessionCount * framesPerSession;
        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(
                        () ->
                                assertThat(recordingUseCase.getTotalRecordedCount())
                                        .isGreaterThanOrEqualTo(expectedTotal));

        for (CallId callId : callIds) {
            assertThat(recordingUseCase.getChunksFor(callId)).hasSize(framesPerSession);
            assertThat(
                            recordingUseCase.verifyStrictMonotonicSequence(
                                    callId, 0L, framesPerSession - 1L))
                    .isTrue();
            assertThat(recordingUseCase.verifyPayloadIntegrity(callId)).isTrue();
            assertThat(recordingUseCase.verifySingleThreadExecution(callId)).isTrue();
        }
    }

    private long getConsumerGroupLag(String consumerGroupId) throws Exception {
        Map<String, Object> adminProps =
                Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        try (AdminClient adminClient = AdminClient.create(adminProps)) {
            Map<TopicPartition, OffsetAndMetadata> groupOffsets =
                    adminClient
                            .listConsumerGroupOffsets(consumerGroupId)
                            .partitionsToOffsetAndMetadata()
                            .get(10, TimeUnit.SECONDS);

            if (groupOffsets.isEmpty()) {
                return 0L;
            }

            Map<TopicPartition, OffsetSpec> topicPartitionOffsetSpecs =
                    groupOffsets.keySet().stream()
                            .collect(Collectors.toMap(tp -> tp, tp -> OffsetSpec.latest()));

            Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> endOffsets =
                    adminClient
                            .listOffsets(topicPartitionOffsetSpecs)
                            .all()
                            .get(10, TimeUnit.SECONDS);

            return groupOffsets.entrySet().stream()
                    .mapToLong(
                            entry -> {
                                long currentOffset = entry.getValue().offset();
                                ListOffsetsResult.ListOffsetsResultInfo endInfo =
                                        endOffsets.get(entry.getKey());
                                if (endInfo == null) {
                                    return 0L;
                                }
                                return Math.max(0L, endInfo.offset() - currentOffset);
                            })
                    .sum();
        }
    }
}
