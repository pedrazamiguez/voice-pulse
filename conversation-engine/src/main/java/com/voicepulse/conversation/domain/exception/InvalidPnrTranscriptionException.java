package com.voicepulse.conversation.domain.exception;

/**
 * Domain exception thrown when a speech-to-text transcript cannot be parsed or normalized into a
 * valid 6-character alphanumeric Passenger Name Record (PNR) per RFC-0003.
 *
 * <p>Pure domain exception with zero framework dependencies.
 */
public class InvalidPnrTranscriptionException extends IllegalArgumentException {

    private final String transcription;

    public InvalidPnrTranscriptionException(String transcription, String reason) {
        super(
                "Unable to parse valid 6-character PNR from transcription: \""
                        + (transcription == null ? "" : transcription)
                        + "\" ("
                        + reason
                        + ")");
        this.transcription = transcription == null ? "" : transcription;
    }

    public String transcription() {
        return transcription;
    }
}
