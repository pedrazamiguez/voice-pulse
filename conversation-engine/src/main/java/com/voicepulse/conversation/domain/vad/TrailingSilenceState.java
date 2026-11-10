package com.voicepulse.conversation.domain.vad;

/**
 * Sealed conversational turn state tracking speech onset filtering, active speech duration, and
 * trailing silence accumulation.
 *
 * <p>Enforces exhaustive pattern matching with zero mutable state outside the sliding-window
 * evaluator.
 */
public sealed interface TrailingSilenceState
        permits TrailingSilenceState.AwaitingSpeech,
                TrailingSilenceState.InSpeech,
                TrailingSilenceState.TurnComplete {

    /** Awaiting caller speech onset; initial silence is tolerated without completing the turn. */
    record AwaitingSpeech(int speechFramesObserved) implements TrailingSilenceState {
        public AwaitingSpeech {
            if (speechFramesObserved < 0) {
                throw new IllegalArgumentException(
                        "speechFramesObserved cannot be negative: " + speechFramesObserved);
            }
        }
    }

    /** Caller speech detected and active; tracking contiguous trailing silence duration. */
    record InSpeech(long trailingSilenceMs) implements TrailingSilenceState {
        public InSpeech {
            if (trailingSilenceMs < 0L) {
                throw new IllegalArgumentException(
                        "trailingSilenceMs cannot be negative: " + trailingSilenceMs);
            }
        }
    }

    /** 500 ms contiguous trailing silence reached; conversational turn boundary marked. */
    record TurnComplete(long finalSilenceDurationMs) implements TrailingSilenceState {
        public TurnComplete {
            if (finalSilenceDurationMs < 0L) {
                throw new IllegalArgumentException(
                        "finalSilenceDurationMs cannot be negative: " + finalSilenceDurationMs);
            }
        }
    }
}
