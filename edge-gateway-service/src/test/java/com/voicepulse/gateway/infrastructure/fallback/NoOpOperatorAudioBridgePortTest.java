package com.voicepulse.gateway.infrastructure.fallback;

import static org.assertj.core.api.Assertions.assertThatCode;

import com.voicepulse.shared.model.AudioChunk;
import com.voicepulse.shared.model.AudioFormat;
import com.voicepulse.shared.model.CallSessionId;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("NoOpOperatorAudioBridgePort Null Object Fallback Tests")
class NoOpOperatorAudioBridgePortTest {

    private final NoOpOperatorAudioBridgePort port = new NoOpOperatorAudioBridgePort();

    @Test
    @DisplayName("all bridge port methods execute safely without exceptions")
    void executesAllMethodsSafely() {
        CallSessionId sessionId = CallSessionId.generate();
        AudioChunk chunk =
                new AudioChunk(
                        sessionId, 0L, Instant.now(), new byte[640], AudioFormat.PCM_16KHZ_MONO);

        assertThatCode(() -> port.activateBridge(sessionId, "op-1")).doesNotThrowAnyException();
        assertThatCode(() -> port.relayCallerAudio(chunk)).doesNotThrowAnyException();
        assertThatCode(() -> port.relayOperatorAudio(sessionId, "op-1", new byte[640]))
                .doesNotThrowAnyException();
        assertThatCode(() -> port.detachOperator(sessionId, "op-1")).doesNotThrowAnyException();
        assertThatCode(() -> port.closeBridge(sessionId)).doesNotThrowAnyException();
    }
}
