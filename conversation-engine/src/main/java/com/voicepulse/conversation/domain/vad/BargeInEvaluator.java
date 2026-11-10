package com.voicepulse.conversation.domain.vad;

import java.util.Objects;

/**
 * Pure domain service evaluating caller speech activity during active bot output turns (barge-in).
 *
 * <p>Per RFC-0001 §4.3 and RFC-0003 §3, caller speech detected while synthetic agent playback is
 * active triggers an immediate barge-in interruption. If the bot is not speaking (turn phase is
 * {@link TurnPhase#LISTENING}), speech is considered standard caller conversational progression and
 * does not trigger a barge-in event.
 */
public class BargeInEvaluator {

    private final VoiceActivityDetector vad;

    /** Constructs evaluator with default telephony VoiceActivityDetector. */
    public BargeInEvaluator() {
        this(new VoiceActivityDetector());
    }

    /**
     * Constructs evaluator with customized VoiceActivityDetector.
     *
     * @param vad voice activity detector
     */
    public BargeInEvaluator(VoiceActivityDetector vad) {
        this.vad = Objects.requireNonNull(vad, "vad cannot be null");
    }

    /**
     * Evaluates a raw 16-bit Linear PCM audio chunk against current bot playback activity.
     *
     * @param pcmData raw signed 16-bit LE PCM byte array
     * @param isAgentPlaying true if agent is actively outputting synthetic speech
     * @return barge-in evaluation result
     */
    public BargeInEvaluationResult evaluate(byte[] pcmData, boolean isAgentPlaying) {
        Objects.requireNonNull(pcmData, "pcmData cannot be null");
        double rms = vad.calculateRms(pcmData);
        double speechProbability = vad.calculateSpeechProbability(rms);
        boolean speechDetected = vad.isSpeech(rms);

        if (!isAgentPlaying) {
            return BargeInEvaluationResult.none(rms, speechProbability);
        }

        return speechDetected
                ? BargeInEvaluationResult.triggered(rms, speechProbability)
                : BargeInEvaluationResult.none(rms, speechProbability);
    }

    /**
     * Evaluates a raw PCM frame against current conversational turn phase.
     *
     * @param pcmData raw signed 16-bit LE PCM byte array
     * @param turnPhase current conversational turn phase
     * @return barge-in evaluation result
     */
    public BargeInEvaluationResult evaluate(byte[] pcmData, TurnPhase turnPhase) {
        Objects.requireNonNull(turnPhase, "turnPhase cannot be null");
        return evaluate(pcmData, turnPhase == TurnPhase.BOT_SPEAKING);
    }

    /**
     * Evaluates a pre-computed VAD result against conversational turn phase.
     *
     * @param vadResult pre-computed VAD evaluation result
     * @param turnPhase current conversational turn phase
     * @return barge-in evaluation result
     */
    public BargeInEvaluationResult evaluateVad(VadEvaluationResult vadResult, TurnPhase turnPhase) {
        Objects.requireNonNull(vadResult, "vadResult cannot be null");
        Objects.requireNonNull(turnPhase, "turnPhase cannot be null");

        boolean bargeIn = (turnPhase == TurnPhase.BOT_SPEAKING) && vadResult.speechDetected();
        return new BargeInEvaluationResult(
                bargeIn, vadResult.rmsEnergy(), vadResult.speechProbability());
    }

    /**
     * Fast check determining whether a PCM frame indicates barge-in during bot speech.
     *
     * @param pcmData raw signed 16-bit LE PCM byte array
     * @param turnPhase current conversational turn phase
     * @return true if barge-in detected
     */
    public boolean isBargeIn(byte[] pcmData, TurnPhase turnPhase) {
        Objects.requireNonNull(turnPhase, "turnPhase cannot be null");
        if (turnPhase != TurnPhase.BOT_SPEAKING) {
            return false;
        }
        return vad.isSpeech(pcmData);
    }
}
