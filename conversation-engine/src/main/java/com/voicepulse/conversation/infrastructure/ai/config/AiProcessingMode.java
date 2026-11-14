package com.voicepulse.conversation.infrastructure.ai.config;

/** Selectable conversational processing strategy per RFC-0001 §4.4 and RFC-0002 §9.3. */
public enum AiProcessingMode {
    /** Deterministic sub-50ms mock dialog engine for high-concurrency load tests. */
    MOCK,
    /** Live OpenAI/Ollama-compatible LLM inference and tool-calling strategy. */
    LIVE
}
