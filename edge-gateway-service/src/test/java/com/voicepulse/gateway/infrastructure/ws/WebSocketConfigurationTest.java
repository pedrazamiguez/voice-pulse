package com.voicepulse.gateway.infrastructure.ws;

import static org.assertj.core.api.Assertions.assertThat;

import com.voicepulse.gateway.domain.ports.inbound.IngestAudioChunkUseCase;
import com.voicepulse.gateway.domain.ports.inbound.ManageCallSessionUseCase;
import com.voicepulse.gateway.infrastructure.registry.ConcurrentSessionRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.Ordered;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping;
import org.springframework.web.reactive.socket.server.support.WebSocketHandlerAdapter;

@ExtendWith(MockitoExtension.class)
@DisplayName("WebSocketConfiguration Unit Tests")
class WebSocketConfigurationTest {

    @Mock private IngestAudioChunkUseCase ingestAudioChunkUseCase;

    @Mock private ManageCallSessionUseCase manageCallSessionUseCase;

    @Mock private ConcurrentSessionRegistry sessionRegistry;

    @Mock private WebSocketCallUriParser uriParser;

    @InjectMocks private AudioWebSocketHandler handler;

    @Test
    @DisplayName(
            "WebSocketConfiguration registers HandlerMapping with correct URL pattern and order")
    void webSocketHandlerMapping_configuresUrlAndPriority() {
        WebSocketConfiguration config = new WebSocketConfiguration();
        HandlerMapping mapping = config.webSocketHandlerMapping(handler);

        assertThat(mapping).isInstanceOf(SimpleUrlHandlerMapping.class);
        SimpleUrlHandlerMapping simpleMapping = (SimpleUrlHandlerMapping) mapping;
        assertThat(simpleMapping.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
        assertThat(simpleMapping.getUrlMap()).containsKey(WebSocketConfiguration.AUDIO_PATH);
        assertThat(simpleMapping.getUrlMap().get(WebSocketConfiguration.AUDIO_PATH))
                .isSameAs(handler);
    }

    @Test
    @DisplayName("WebSocketConfiguration provides WebSocketHandlerAdapter bean")
    void webSocketHandlerAdapter_instantiatesCleanly() {
        WebSocketConfiguration config = new WebSocketConfiguration();
        WebSocketHandlerAdapter adapter = config.webSocketHandlerAdapter();
        assertThat(adapter).isNotNull();
    }
}
