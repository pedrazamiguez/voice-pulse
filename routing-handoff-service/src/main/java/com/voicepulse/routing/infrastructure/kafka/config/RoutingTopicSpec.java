package com.voicepulse.routing.infrastructure.kafka.config;

/**
 * Fail-fast partition and replication topology settings for an individual Kafka topic.
 *
 * @param partitions number of broker partitions ({@code > 0})
 * @param replicas replication factor ({@code > 0})
 */
public record RoutingTopicSpec(int partitions, int replicas) {

    public RoutingTopicSpec {
        if (partitions <= 0) {
            throw new IllegalArgumentException(
                    "partitions must be positive, but was: " + partitions);
        }
        if (replicas <= 0) {
            throw new IllegalArgumentException("replicas must be positive, but was: " + replicas);
        }
    }
}
