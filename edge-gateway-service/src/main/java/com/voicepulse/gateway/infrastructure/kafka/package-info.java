/**
 * Kafka producer adapters for audio frames and telephony signaling events.
 *
 * <p>Kafka partitioning invariant: all events produced to telephony topics MUST use
 * callId.toString() as the partition key.
 */
package com.voicepulse.gateway.infrastructure.kafka;
