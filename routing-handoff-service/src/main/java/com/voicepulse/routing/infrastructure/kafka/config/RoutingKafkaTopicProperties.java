package com.voicepulse.routing.infrastructure.kafka.config;

/**
 * Grouped Kafka topic topology configuration bound under {@code voicepulse.routing.kafka.topics}.
 *
 * @param ticketIntent topology for {@code telephony.tickets.intent}
 * @param callControl topology for {@code telephony.calls.control}
 * @param dlq topology for {@code telephony.calls.dlq}
 */
public record RoutingKafkaTopicProperties(
        RoutingTopicSpec ticketIntent, RoutingTopicSpec callControl, RoutingTopicSpec dlq) {

    public RoutingKafkaTopicProperties {
        if (ticketIntent == null) {
            throw new IllegalArgumentException("topics.ticketIntent must not be null");
        }
        if (callControl == null) {
            throw new IllegalArgumentException("topics.callControl must not be null");
        }
        if (dlq == null) {
            throw new IllegalArgumentException("topics.dlq must not be null");
        }
    }
}
