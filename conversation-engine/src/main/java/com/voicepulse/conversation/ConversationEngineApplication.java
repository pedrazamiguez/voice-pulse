package com.voicepulse.conversation;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for VoicePulse Conversation Engine.
 *
 * <p>Orchestrates the conversational state machine, VAD turn detection, real-time AI triage, and
 * airline disruption ticket generation per RFC-0003.
 */
@SpringBootApplication
public class ConversationEngineApplication {

    public static void main(String[] args) {
        SpringApplication.run(ConversationEngineApplication.class, args);
    }
}
