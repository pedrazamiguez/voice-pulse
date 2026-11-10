package com.voicepulse.conversation.domain.statemachine;

import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Sealed interface representing the bounded conversational lifecycle states for the SkyWay Airlines
 * Disruption Hotline (RFC-0003 and RFC-0001 §4.3).
 *
 * <p>Enforces strict immutability and exhaustive pattern matching across conversational turns.
 */
public sealed interface CallSessionState
        permits CallSessionState.Connected,
                CallSessionState.Greeting,
                CallSessionState.CollectingPassengerDetails,
                CallSessionState.AwaitingBookingConfirmation,
                CallSessionState.DisruptionTriage,
                CallSessionState.HandoffReady,
                CallSessionState.Transferred,
                CallSessionState.Terminated {

    /** Session is established and awaiting initial bot greeting. */
    record Connected(Instant connectedAt) implements CallSessionState {
        public Connected {
            Objects.requireNonNull(connectedAt, "connectedAt cannot be null");
        }
    }

    /** Bot greeting has been prompted to the caller. */
    record Greeting(Instant greetedAt) implements CallSessionState {
        public Greeting {
            Objects.requireNonNull(greetedAt, "greetedAt cannot be null");
        }
    }

    /** Prompted caller for 6-character PNR booking reference. */
    record CollectingPassengerDetails(int attemptCount) implements CallSessionState {
        public CollectingPassengerDetails {
            if (attemptCount < 1) {
                throw new IllegalArgumentException("attemptCount must be >= 1: " + attemptCount);
            }
        }
    }

    /** Acoustic NATO phonetic confirmation prompted, awaiting caller confirmation. */
    record AwaitingBookingConfirmation(String transcribedPnr, Instant promptedAt)
            implements CallSessionState {
        public AwaitingBookingConfirmation {
            Objects.requireNonNull(transcribedPnr, "transcribedPnr cannot be null");
            Objects.requireNonNull(promptedAt, "promptedAt cannot be null");
        }
    }

    /** Booking confirmed; collecting disruption preferences and compiling ticket details. */
    record DisruptionTriage(String pnr, String customerName, String flightNumber, Instant triagedAt)
            implements CallSessionState {

        private static final Pattern PNR_PATTERN = Pattern.compile("^[A-Za-z0-9]{6}$");

        public DisruptionTriage {
            Objects.requireNonNull(pnr, "pnr cannot be null");
            Objects.requireNonNull(customerName, "customerName cannot be null");
            Objects.requireNonNull(flightNumber, "flightNumber cannot be null");
            Objects.requireNonNull(triagedAt, "triagedAt cannot be null");

            if (!PNR_PATTERN.matcher(pnr).matches()) {
                throw new IllegalArgumentException(
                        "PNR must be exactly 6 alphanumeric characters: " + pnr);
            }
        }
    }

    /** Structured disruption ticket finalized; queued for priority operator routing. */
    record HandoffReady(String ticketId, String targetSkillGroup) implements CallSessionState {
        public HandoffReady {
            Objects.requireNonNull(ticketId, "ticketId cannot be null");
            Objects.requireNonNull(targetSkillGroup, "targetSkillGroup cannot be null");
        }
    }

    /** Call bridged to human operator console. */
    record Transferred(String operatorId, Instant transferredAt) implements CallSessionState {
        public Transferred {
            Objects.requireNonNull(operatorId, "operatorId cannot be null");
            Objects.requireNonNull(transferredAt, "transferredAt cannot be null");
        }
    }

    /** Call session terminated and ready for resource cleanup. */
    record Terminated(String reason, Instant terminatedAt) implements CallSessionState {
        public Terminated {
            Objects.requireNonNull(reason, "reason cannot be null");
            Objects.requireNonNull(terminatedAt, "terminatedAt cannot be null");
        }
    }
}
