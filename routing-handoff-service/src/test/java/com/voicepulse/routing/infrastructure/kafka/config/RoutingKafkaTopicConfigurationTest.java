package com.voicepulse.routing.infrastructure.kafka.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

@DisplayName("RoutingKafkaTopicConfiguration Unit Tests")
class RoutingKafkaTopicConfigurationTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withUserConfiguration(RoutingKafkaTopicConfiguration.class)
                    .withPropertyValues(
                            "voicepulse.routing.ticket-intent-topic=custom.tickets.intent",
                            "voicepulse.routing.call-control-topic=custom.calls.control",
                            "voicepulse.routing.consumer-group=custom-workers",
                            "voicepulse.routing.operator-heartbeat-ttl=30s",
                            "voicepulse.routing.operator-bridge-base-path=/ws/operators",
                            "voicepulse.routing.max-queue-capacity=500",
                            "voicepulse.routing.kafka.enabled=true",
                            "voicepulse.routing.kafka.concurrency=2",
                            "voicepulse.routing.kafka.auto-offset-reset=earliest",
                            "voicepulse.routing.kafka.send-timeout=4s",
                            "voicepulse.routing.kafka.retry-backoff=250ms",
                            "voicepulse.routing.kafka.max-retries=1",
                            "voicepulse.routing.kafka.dlq-topic=custom.calls.dlq",
                            "voicepulse.routing.kafka.topics.ticket-intent.partitions=9",
                            "voicepulse.routing.kafka.topics.ticket-intent.replicas=3",
                            "voicepulse.routing.kafka.topics.call-control.partitions=24",
                            "voicepulse.routing.kafka.topics.call-control.replicas=3",
                            "voicepulse.routing.kafka.topics.dlq.partitions=4",
                            "voicepulse.routing.kafka.topics.dlq.replicas=2");

    @Test
    @DisplayName("Should provision NewTopic beans using partitions and replicas from properties")
    void shouldBindTopicPartitionsAndReplicasFromProperties() {
        contextRunner.run(
                context -> {
                    NewTopic ticketIntent =
                            context.getBean("routingTicketIntentTopic", NewTopic.class);
                    NewTopic callControl =
                            context.getBean("routingCallControlTopic", NewTopic.class);
                    NewTopic dlq = context.getBean("routingCallsDlqTopic", NewTopic.class);

                    assertThat(ticketIntent.name()).isEqualTo("custom.tickets.intent");
                    assertThat(ticketIntent.numPartitions()).isEqualTo(9);
                    assertThat(ticketIntent.replicationFactor()).isEqualTo((short) 3);

                    assertThat(callControl.name()).isEqualTo("custom.calls.control");
                    assertThat(callControl.numPartitions()).isEqualTo(24);
                    assertThat(callControl.replicationFactor()).isEqualTo((short) 3);

                    assertThat(dlq.name()).isEqualTo("custom.calls.dlq");
                    assertThat(dlq.numPartitions()).isEqualTo(4);
                    assertThat(dlq.replicationFactor()).isEqualTo((short) 2);
                });
    }
}
