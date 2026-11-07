/**
 * Kafka consumer and publisher adapters for conversational signaling and audio frames.
 *
 * <p>Strict in-order consumption invariant: events for a given callId must be consumed in monotonic
 * sequenceNumber order.
 */
package com.voicepulse.conversation.infrastructure.kafka;
