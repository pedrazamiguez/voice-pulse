package com.voicepulse.conversation.domain.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.voicepulse.conversation.domain.statemachine.CallSessionState;
import com.voicepulse.shared.model.CallId;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("ConversationDomainEvent Sealed Hierarchy Domain Tests")
class ConversationDomainEventTest {

    @Nested
    @DisplayName("CallStateTransitioned Invariants")
    class CallStateTransitionedTests {

        @Test
        @DisplayName("valid state transition event instantiated cleanly")
        void validStateTransitionEvent() {
            CallId callId = CallId.generate();
            Instant now = Instant.now();
            CallSessionState s1 = new CallSessionState.Connected(now);
            CallSessionState s2 = new CallSessionState.Greeting(now.plusSeconds(1));

            CallStateTransitioned event = new CallStateTransitioned(callId, s1, s2, now);

            assertThat(event.callId()).isEqualTo(callId);
            assertThat(event.previousState()).isEqualTo(s1);
            assertThat(event.newState()).isEqualTo(s2);
            assertThat(event.occurredAt()).isEqualTo(now);
        }

        @Test
        @DisplayName("null parameters are rejected")
        void rejectsNulls() {
            CallId callId = CallId.generate();
            Instant now = Instant.now();
            CallSessionState s1 = new CallSessionState.Connected(now);

            assertThatThrownBy(() -> new CallStateTransitioned(null, s1, s1, now))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new CallStateTransitioned(callId, null, s1, now))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new CallStateTransitioned(callId, s1, null, now))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new CallStateTransitioned(callId, s1, s1, null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("TurnCompleted Invariants")
    class TurnCompletedTests {

        @Test
        @DisplayName("valid turn completed event instantiated cleanly")
        void validTurnCompletedEvent() {
            CallId callId = CallId.generate();
            Instant now = Instant.now();
            CallSessionState state = new CallSessionState.Greeting(now);

            TurnCompleted event = new TurnCompleted(callId, 2, state, now);

            assertThat(event.callId()).isEqualTo(callId);
            assertThat(event.turnNumber()).isEqualTo(2);
            assertThat(event.currentState()).isEqualTo(state);
            assertThat(event.occurredAt()).isEqualTo(now);
        }

        @Test
        @DisplayName("turnNumber < 1 is rejected")
        void rejectsInvalidTurnNumber() {
            CallId callId = CallId.generate();
            Instant now = Instant.now();
            CallSessionState state = new CallSessionState.Greeting(now);

            assertThatThrownBy(() -> new TurnCompleted(callId, 0, state, now))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("turnNumber must be >= 1");
        }
    }

    @Nested
    @DisplayName("BargeInDetected Invariants")
    class BargeInDetectedTests {

        @Test
        @DisplayName("valid barge-in event instantiated cleanly")
        void validBargeInEvent() {
            CallId callId = CallId.generate();
            Instant now = Instant.now();
            CallSessionState state = new CallSessionState.Greeting(now);

            BargeInDetected event = new BargeInDetected(callId, state, now);

            assertThat(event.callId()).isEqualTo(callId);
            assertThat(event.currentState()).isEqualTo(state);
            assertThat(event.occurredAt()).isEqualTo(now);
        }
    }

    @Nested
    @DisplayName("Pattern Matching")
    class PatternMatchingTests {

        @Test
        @DisplayName("exhaustive pattern matching covers all domain events")
        void patternMatchingCoversAllEvents() {
            CallId callId = CallId.generate();
            Instant now = Instant.now();
            CallSessionState state = new CallSessionState.Connected(now);

            ConversationDomainEvent[] events = {
                new CallStateTransitioned(callId, state, new CallSessionState.Greeting(now), now),
                new TurnCompleted(callId, 1, state, now),
                new BargeInDetected(callId, state, now)
            };

            for (ConversationDomainEvent event : events) {
                String type =
                        switch (event) {
                            case CallStateTransitioned cst -> "TRANSITION";
                            case TurnCompleted tc -> "TURN";
                            case BargeInDetected bid -> "BARGE_IN";
                        };
                assertThat(type).isNotEmpty();
            }
        }
    }
}
