package com.voicepulse.conversation.infrastructure.kafka.producer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.instancio.Select.field;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.voicepulse.conversation.domain.ports.outbound.TicketIntentPublisherPort;
import com.voicepulse.conversation.infrastructure.fallback.NoOpTicketIntentPublisherPort;
import com.voicepulse.conversation.infrastructure.kafka.config.ConversationKafkaTicketIntentProducerConfiguration;
import com.voicepulse.conversation.infrastructure.kafka.config.KafkaTopicProperties;
import com.voicepulse.conversation.infrastructure.kafka.config.KafkaTopicProperties.TopicConfig;
import com.voicepulse.shared.event.TicketFormFilledEvent;
import com.voicepulse.shared.model.CallId;
import com.voicepulse.shared.model.FlightNumber;
import com.voicepulse.shared.model.Pnr;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.instancio.Instancio;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

@ExtendWith(MockitoExtension.class)
@DisplayName("KafkaTicketIntentPublisher Unit Tests")
class KafkaTicketIntentPublisherTest {

    private static final String TICKETS_INTENT_TOPIC = "telephony.tickets.intent";

    @Mock private KafkaTemplate<String, TicketFormFilledEvent> kafkaTemplate;

    @Captor private ArgumentCaptor<ProducerRecord<String, TicketFormFilledEvent>> recordCaptor;

    private KafkaTopicProperties topicProperties;
    private KafkaTicketIntentPublisher publisher;

    @BeforeEach
    void setUp() {
        TopicConfig raw =
                new TopicConfig("telephony.calls.raw", 12, (short) 1, Duration.ofHours(1), null);
        TopicConfig control =
                new TopicConfig(
                        "telephony.calls.control", 12, (short) 1, Duration.ofHours(1), null);
        TopicConfig ticketsIntent =
                new TopicConfig(TICKETS_INTENT_TOPIC, 6, (short) 1, Duration.ofHours(168), null);
        TopicConfig dlq =
                new TopicConfig("telephony.calls.dlq", 3, (short) 1, Duration.ofHours(336), null);
        topicProperties = new KafkaTopicProperties(raw, control, ticketsIntent, dlq);

        publisher = new KafkaTicketIntentPublisher(kafkaTemplate, topicProperties);
    }

    @Test
    @DisplayName(
            "publishTicketFilled sends ProducerRecord to telephony.tickets.intent strictly keyed by"
                    + " callId.toString()")
    @SuppressWarnings("unchecked")
    void publishTicketFilled_publishesRecordStrictlyKeyedByCallId() {
        CallId callId = CallId.generate();
        Instant now = Instant.now();
        TicketFormFilledEvent event = createTicketEvent(callId, now);

        RecordMetadata metadata =
                new RecordMetadata(
                        new TopicPartition(TICKETS_INTENT_TOPIC, 2),
                        0L,
                        0,
                        now.toEpochMilli(),
                        0,
                        0);
        SendResult<String, TicketFormFilledEvent> sendResult = new SendResult<>(null, metadata);
        CompletableFuture<SendResult<String, TicketFormFilledEvent>> future =
                CompletableFuture.completedFuture(sendResult);

        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(future);

        publisher.publishTicketFilled(event);

        verify(kafkaTemplate).send(recordCaptor.capture());

        ProducerRecord<String, TicketFormFilledEvent> captured = recordCaptor.getValue();
        assertThat(captured.topic()).isEqualTo(TICKETS_INTENT_TOPIC);
        assertThat(captured.key()).isEqualTo(callId.toString());
        assertThat(captured.value()).isEqualTo(event);
    }

    @Test
    @DisplayName("publishTicketFilled handles asynchronous Kafka broker failure gracefully")
    @SuppressWarnings("unchecked")
    void publishTicketFilled_handlesAsyncKafkaFailureGracefully() {
        CallId callId = CallId.generate();
        TicketFormFilledEvent event = createTicketEvent(callId, Instant.now());

        CompletableFuture<SendResult<String, TicketFormFilledEvent>> failedFuture =
                CompletableFuture.failedFuture(new RuntimeException("Broker unavailable"));

        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(failedFuture);

        publisher.publishTicketFilled(event);

        verify(kafkaTemplate).send(recordCaptor.capture());
        assertThat(recordCaptor.getValue().key()).isEqualTo(callId.toString());
    }

    @Test
    @DisplayName("publishTicketFilled rejects null event")
    void publishTicketFilled_rejectsNullEvent() {
        assertThatThrownBy(() -> publisher.publishTicketFilled(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("event must not be null");
    }

    @Test
    @DisplayName("KafkaTicketIntentPublisher supersedes NoOpTicketIntentPublisherPort in Spring")
    void springContext_supersedesNoOpFallbackBean() {
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withConfiguration(AutoConfigurations.of(KafkaAutoConfiguration.class))
                .withUserConfiguration(
                        ConversationKafkaTicketIntentProducerConfiguration.class,
                        NoOpTicketIntentPublisherPort.class,
                        KafkaTicketIntentPublisher.class)
                .run(
                        context -> {
                            assertThat(context).hasSingleBean(KafkaTicketIntentPublisher.class);
                            assertThat(context).hasBean("ticketIntentProducerFactory");
                            assertThat(context).hasBean("ticketIntentKafkaTemplate");
                            TicketIntentPublisherPort port =
                                    context.getBean(TicketIntentPublisherPort.class);
                            assertThat(port)
                                    .isInstanceOf(KafkaTicketIntentPublisher.class)
                                    .isNotInstanceOf(NoOpTicketIntentPublisherPort.class);
                        });
    }

    private static TicketFormFilledEvent createTicketEvent(CallId callId, Instant timestamp) {
        return Instancio.of(TicketFormFilledEvent.class)
                .set(field(TicketFormFilledEvent::callId), callId)
                .set(field(TicketFormFilledEvent::passengerName), "Andres")
                .set(field(TicketFormFilledEvent::pnr), new Pnr("A8F9X2"))
                .set(field(TicketFormFilledEvent::originalFlightNumber), new FlightNumber("SW-104"))
                .set(field(TicketFormFilledEvent::targetSkillGroup), "DISRUPTION_DESK_GOLD")
                .set(
                        field(TicketFormFilledEvent::payload),
                        Map.of("origin", "CTU", "destination", "MAD"))
                .set(field(TicketFormFilledEvent::timestamp), timestamp)
                .create();
    }
}
