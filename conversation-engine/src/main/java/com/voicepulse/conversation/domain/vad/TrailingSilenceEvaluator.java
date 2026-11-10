package com.voicepulse.conversation.domain.vad;

import com.voicepulse.shared.model.AudioChunk;
import java.util.Objects;

/**
 * Sliding window evaluator detecting speech onset filtering and 500 ms contiguous trailing silence
 * marking caller turn completion.
 *
 * <p>Coordinates DSP frame energy analysis with an exhaustive conversational turn state machine.
 */
public class TrailingSilenceEvaluator {

    private final VoiceActivityDetector detector;
    private final VadThresholdConfig config;
    private TrailingSilenceState state;

    /** Constructs evaluator with default detector and thresholds. */
    public TrailingSilenceEvaluator() {
        this(new VoiceActivityDetector());
    }

    /**
     * Constructs evaluator with specified DSP detector.
     *
     * @param detector voice activity detector instance
     */
    public TrailingSilenceEvaluator(VoiceActivityDetector detector) {
        this.detector = Objects.requireNonNull(detector, "detector cannot be null");
        this.config = detector.config();
        this.state = new TrailingSilenceState.AwaitingSpeech(0);
    }

    /**
     * Evaluates an incoming audio frame and advances the conversational turn state machine.
     *
     * @param pcmData raw signed 16-bit LE PCM byte array
     * @return evaluation result with frame metrics and turn boundary indicator
     */
    public synchronized VadEvaluationResult evaluate(byte[] pcmData) {
        double rms = detector.calculateRms(pcmData);
        double probability = detector.calculateSpeechProbability(rms);
        boolean speech = detector.isSpeech(rms);

        long silenceMs;
        boolean turnBoundary;

        switch (this.state) {
            case TrailingSilenceState.AwaitingSpeech awaiting -> {
                if (speech) {
                    int observed = awaiting.speechFramesObserved() + 1;
                    if (observed >= config.minSpeechFrames()) {
                        this.state = new TrailingSilenceState.InSpeech(0L);
                    } else {
                        this.state = new TrailingSilenceState.AwaitingSpeech(observed);
                    }
                } else {
                    this.state = new TrailingSilenceState.AwaitingSpeech(0);
                }
                silenceMs = 0L;
                turnBoundary = false;
            }
            case TrailingSilenceState.InSpeech inSpeech -> {
                if (speech) {
                    this.state = new TrailingSilenceState.InSpeech(0L);
                    silenceMs = 0L;
                    turnBoundary = false;
                } else {
                    long newSilence = inSpeech.trailingSilenceMs() + config.frameDurationMs();
                    silenceMs = newSilence;
                    if (newSilence >= config.silenceThresholdMs()) {
                        this.state = new TrailingSilenceState.TurnComplete(newSilence);
                        turnBoundary = true;
                    } else {
                        this.state = new TrailingSilenceState.InSpeech(newSilence);
                        turnBoundary = false;
                    }
                }
            }
            case TrailingSilenceState.TurnComplete complete -> {
                long newSilence = complete.finalSilenceDurationMs() + config.frameDurationMs();
                this.state = new TrailingSilenceState.TurnComplete(newSilence);
                silenceMs = newSilence;
                turnBoundary = true;
            }
        }

        return new VadEvaluationResult(rms, probability, speech, silenceMs, turnBoundary);
    }

    /**
     * Evaluates an incoming {@link AudioChunk} domain record.
     *
     * @param chunk monotonic audio chunk envelope
     * @return evaluation result
     * @throws NullPointerException if chunk is null
     */
    public VadEvaluationResult evaluate(AudioChunk chunk) {
        Objects.requireNonNull(chunk, "chunk cannot be null");
        return evaluate(chunk.pcmData());
    }

    /** Resets the evaluator to {@link TrailingSilenceState.AwaitingSpeech} for the next turn. */
    public synchronized void reset() {
        this.state = new TrailingSilenceState.AwaitingSpeech(0);
    }

    /** Returns current turn state. */
    public synchronized TrailingSilenceState state() {
        return this.state;
    }

    /** Indicates whether caller turn completion has been reached. */
    public synchronized boolean isTurnComplete() {
        return this.state instanceof TrailingSilenceState.TurnComplete;
    }

    /** Returns underlying threshold configuration. */
    public VadThresholdConfig config() {
        return this.config;
    }

    /** Returns underlying DSP detector. */
    public VoiceActivityDetector detector() {
        return this.detector;
    }
}
