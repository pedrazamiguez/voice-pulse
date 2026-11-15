package com.voicepulse.routing.infrastructure.kafka.config;

import com.voicepulse.routing.infrastructure.config.RoutingProperties;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Spring Kafka programmatic topic provisioning configuration for {@code routing-handoff-service}.
 */
@Configuration
@EnableConfigurationProperties({RoutingKafkaProperties.class, RoutingProperties.class})
@ConditionalOnProperty(
        prefix = "voicepulse.routing.kafka",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
@RequiredArgsConstructor
public class RoutingKafkaTopicConfiguration {

    private final @NonNull RoutingProperties routingProperties;
    private final @NonNull RoutingKafkaProperties routingKafkaProperties;

    @Bean
    public NewTopic routingTicketIntentTopic() {
        return buildTopic(
                routingProperties.ticketIntentTopic(),
                routingKafkaProperties.topics().ticketIntent());
    }

    @Bean
    public NewTopic routingCallControlTopic() {
        return buildTopic(
                routingProperties.callControlTopic(),
                routingKafkaProperties.topics().callControl());
    }

    @Bean
    public NewTopic routingCallsDlqTopic() {
        return buildTopic(routingKafkaProperties.dlqTopic(), routingKafkaProperties.topics().dlq());
    }

    private static NewTopic buildTopic(String topicName, RoutingTopicSpec topicSpec) {
        return TopicBuilder.name(topicName)
                .partitions(topicSpec.partitions())
                .replicas(topicSpec.replicas())
                .build();
    }
}
