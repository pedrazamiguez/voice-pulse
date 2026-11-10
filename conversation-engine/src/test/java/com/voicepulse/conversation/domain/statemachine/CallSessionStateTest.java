package com.voicepulse.conversation.domain.statemachine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("CallSessionState Sealed Hierarchy Domain Tests")
class CallSessionStateTest {

    @Nested
    @DisplayName("Connected State Invariants")
    class ConnectedStateTests {

        @Test
        @DisplayName("valid connected state instantiated cleanly")
        void validConnectedState() {
            Instant now = Instant.now();
            CallSessionState.Connected connected = new CallSessionState.Connected(now);
            assertThat(connected.connectedAt()).isEqualTo(now);
        }

        @Test
        @DisplayName("null connectedAt is rejected")
        void rejectsNullConnectedAt() {
            assertThatThrownBy(() -> new CallSessionState.Connected(null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("connectedAt cannot be null");
        }
    }

    @Nested
    @DisplayName("Greeting State Invariants")
    class GreetingStateTests {

        @Test
        @DisplayName("valid greeting state instantiated cleanly")
        void validGreetingState() {
            Instant now = Instant.now();
            CallSessionState.Greeting greeting = new CallSessionState.Greeting(now);
            assertThat(greeting.greetedAt()).isEqualTo(now);
        }

        @Test
        @DisplayName("null greetedAt is rejected")
        void rejectsNullGreetedAt() {
            assertThatThrownBy(() -> new CallSessionState.Greeting(null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("greetedAt cannot be null");
        }
    }

    @Nested
    @DisplayName("CollectingPassengerDetails State Invariants")
    class CollectingPassengerDetailsTests {

        @Test
        @DisplayName("valid attempt count creates state")
        void validAttemptCount() {
            CallSessionState.CollectingPassengerDetails state =
                    new CallSessionState.CollectingPassengerDetails(1);
            assertThat(state.attemptCount()).isEqualTo(1);
        }

        @ParameterizedTest(name = "attemptCount={0} rejected")
        @ValueSource(ints = {0, -1, -5})
        @DisplayName("attempt count < 1 is rejected")
        void rejectsInvalidAttemptCount(int invalidCount) {
            assertThatThrownBy(() -> new CallSessionState.CollectingPassengerDetails(invalidCount))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("attemptCount must be >= 1");
        }
    }

    @Nested
    @DisplayName("AwaitingBookingConfirmation State Invariants")
    class AwaitingBookingConfirmationTests {

        @Test
        @DisplayName("valid candidate PNR and prompt timestamp create state")
        void validAwaitingConfirmationState() {
            Instant now = Instant.now();
            CallSessionState.AwaitingBookingConfirmation state =
                    new CallSessionState.AwaitingBookingConfirmation("A8F9X2", now);
            assertThat(state.transcribedPnr()).isEqualTo("A8F9X2");
            assertThat(state.promptedAt()).isEqualTo(now);
        }

        @Test
        @DisplayName("null transcribedPnr is rejected")
        void rejectsNullTranscribedPnr() {
            Instant now = Instant.now();
            assertThatThrownBy(() -> new CallSessionState.AwaitingBookingConfirmation(null, now))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("transcribedPnr cannot be null");
        }

        @Test
        @DisplayName("null promptedAt is rejected")
        void rejectsNullPromptedAt() {
            assertThatThrownBy(
                            () -> new CallSessionState.AwaitingBookingConfirmation("A8F9X2", null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("promptedAt cannot be null");
        }
    }

    @Nested
    @DisplayName("DisruptionTriage State Invariants")
    class DisruptionTriageTests {

        @Test
        @DisplayName("valid verified PNR and details create state")
        void validTriageState() {
            Instant now = Instant.now();
            CallSessionState.DisruptionTriage state =
                    new CallSessionState.DisruptionTriage(
                            "A8F9X2", "Andres Pedraza", "SW-104", now);
            assertThat(state.pnr()).isEqualTo("A8F9X2");
            assertThat(state.customerName()).isEqualTo("Andres Pedraza");
            assertThat(state.flightNumber()).isEqualTo("SW-104");
            assertThat(state.triagedAt()).isEqualTo(now);
        }

        @ParameterizedTest(name = "invalid PNR=''{0}''")
        @ValueSource(strings = {"", "ABC", "12345", "TOOLON12", "AB-123", "A8F 9X"})
        @DisplayName("non-6-character alphanumeric PNR is rejected")
        void rejectsInvalidPnr(String invalidPnr) {
            Instant now = Instant.now();
            assertThatThrownBy(
                            () ->
                                    new CallSessionState.DisruptionTriage(
                                            invalidPnr, "Passenger", "SW-104", now))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("PNR must be exactly 6 alphanumeric characters");
        }

        @Test
        @DisplayName("null customerName is rejected")
        void rejectsNullCustomerName() {
            Instant now = Instant.now();
            assertThatThrownBy(
                            () ->
                                    new CallSessionState.DisruptionTriage(
                                            "A8F9X2", null, "SW-104", now))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("customerName cannot be null");
        }

        @Test
        @DisplayName("null flightNumber is rejected")
        void rejectsNullFlightNumber() {
            Instant now = Instant.now();
            assertThatThrownBy(
                            () ->
                                    new CallSessionState.DisruptionTriage(
                                            "A8F9X2", "Passenger", null, now))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("flightNumber cannot be null");
        }

        @Test
        @DisplayName("null triagedAt is rejected")
        void rejectsNullTriagedAt() {
            assertThatThrownBy(
                            () ->
                                    new CallSessionState.DisruptionTriage(
                                            "A8F9X2", "Passenger", "SW-104", null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("triagedAt cannot be null");
        }
    }

    @Nested
    @DisplayName("HandoffReady State Invariants")
    class HandoffReadyTests {

        @Test
        @DisplayName("valid handoff state instantiated cleanly")
        void validHandoffReadyState() {
            CallSessionState.HandoffReady state =
                    new CallSessionState.HandoffReady("TKT-SW-101", "DISRUPTION_DESK_GOLD");
            assertThat(state.ticketId()).isEqualTo("TKT-SW-101");
            assertThat(state.targetSkillGroup()).isEqualTo("DISRUPTION_DESK_GOLD");
        }

        @Test
        @DisplayName("null ticketId is rejected")
        void rejectsNullTicketId() {
            assertThatThrownBy(
                            () -> new CallSessionState.HandoffReady(null, "DISRUPTION_DESK_GOLD"))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("ticketId cannot be null");
        }

        @Test
        @DisplayName("null targetSkillGroup is rejected")
        void rejectsNullTargetSkillGroup() {
            assertThatThrownBy(() -> new CallSessionState.HandoffReady("TKT-SW-101", null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("targetSkillGroup cannot be null");
        }
    }

    @Nested
    @DisplayName("Transferred State Invariants")
    class TransferredTests {

        @Test
        @DisplayName("valid transferred state instantiated cleanly")
        void validTransferredState() {
            Instant now = Instant.now();
            CallSessionState.Transferred state = new CallSessionState.Transferred("OP-449", now);
            assertThat(state.operatorId()).isEqualTo("OP-449");
            assertThat(state.transferredAt()).isEqualTo(now);
        }

        @Test
        @DisplayName("null operatorId is rejected")
        void rejectsNullOperatorId() {
            Instant now = Instant.now();
            assertThatThrownBy(() -> new CallSessionState.Transferred(null, now))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("operatorId cannot be null");
        }

        @Test
        @DisplayName("null transferredAt is rejected")
        void rejectsNullTransferredAt() {
            assertThatThrownBy(() -> new CallSessionState.Transferred("OP-449", null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("transferredAt cannot be null");
        }
    }

    @Nested
    @DisplayName("Terminated State Invariants")
    class TerminatedTests {

        @Test
        @DisplayName("valid terminated state instantiated cleanly")
        void validTerminatedState() {
            Instant now = Instant.now();
            CallSessionState.Terminated state =
                    new CallSessionState.Terminated("NORMAL_HANGUP", now);
            assertThat(state.reason()).isEqualTo("NORMAL_HANGUP");
            assertThat(state.terminatedAt()).isEqualTo(now);
        }

        @Test
        @DisplayName("null reason is rejected")
        void rejectsNullReason() {
            Instant now = Instant.now();
            assertThatThrownBy(() -> new CallSessionState.Terminated(null, now))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("reason cannot be null");
        }

        @Test
        @DisplayName("null terminatedAt is rejected")
        void rejectsNullTerminatedAt() {
            assertThatThrownBy(() -> new CallSessionState.Terminated("HANGUP", null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("terminatedAt cannot be null");
        }
    }

    @Nested
    @DisplayName("Exhaustive Pattern Matching")
    class PatternMatchingTests {

        @Test
        @DisplayName("exhaustive switch matches all 8 permits without default branch")
        void exhaustiveSwitchMatching() {
            Instant now = Instant.now();
            CallSessionState[] states = {
                new CallSessionState.Connected(now),
                new CallSessionState.Greeting(now),
                new CallSessionState.CollectingPassengerDetails(1),
                new CallSessionState.AwaitingBookingConfirmation("A8F9X2", now),
                new CallSessionState.DisruptionTriage("A8F9X2", "Andres", "SW-101", now),
                new CallSessionState.HandoffReady("TKT-1", "DESK"),
                new CallSessionState.Transferred("OP-1", now),
                new CallSessionState.Terminated("DONE", now)
            };

            for (CallSessionState state : states) {
                String label =
                        switch (state) {
                            case CallSessionState.Connected c -> "CONNECTED";
                            case CallSessionState.Greeting g -> "GREETING";
                            case CallSessionState.CollectingPassengerDetails cp -> "COLLECTING";
                            case CallSessionState.AwaitingBookingConfirmation ac -> "AWAITING";
                            case CallSessionState.DisruptionTriage dt -> "TRIAGE";
                            case CallSessionState.HandoffReady hr -> "HANDOFF";
                            case CallSessionState.Transferred tr -> "TRANSFERRED";
                            case CallSessionState.Terminated te -> "TERMINATED";
                        };
                assertThat(label).isNotEmpty();
            }
        }
    }
}
