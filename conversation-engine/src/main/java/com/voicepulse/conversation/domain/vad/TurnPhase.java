package com.voicepulse.conversation.domain.vad;

/**
 * Conversational turn phase representing whether the conversational engine is currently listening
 * for caller speech or actively outputting/synthesizing bot speech.
 */
public enum TurnPhase {
    LISTENING,
    BOT_SPEAKING
}
