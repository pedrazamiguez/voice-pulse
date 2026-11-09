package com.voicepulse.gateway.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest
@ActiveProfiles("test")
@DisplayName("Softphone WebFlux Routing & Static Resource Integration Tests")
class SoftphoneWebConfigurationTest {

    private final ApplicationContext applicationContext;
    private WebTestClient webTestClient;

    @Autowired
    SoftphoneWebConfigurationTest(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    @BeforeEach
    void setUp() {
        this.webTestClient = WebTestClient.bindToApplicationContext(applicationContext).build();
    }

    @Test
    @DisplayName("GET / redirects to /softphone/index.html with 307 Temporary Redirect")
    void getRoot_redirectsToSoftphoneIndex() {
        webTestClient
                .get()
                .uri("/")
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.TEMPORARY_REDIRECT)
                .expectHeader()
                .location("/softphone/index.html");
    }

    @Test
    @DisplayName("GET /softphone redirects to /softphone/index.html with 307 Temporary Redirect")
    void getSoftphone_redirectsToSoftphoneIndex() {
        webTestClient
                .get()
                .uri("/softphone")
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.TEMPORARY_REDIRECT)
                .expectHeader()
                .location("/softphone/index.html");
    }

    @Test
    @DisplayName("GET /softphone/ redirects to /softphone/index.html with 307 Temporary Redirect")
    void getSoftphoneTrailingSlash_redirectsToSoftphoneIndex() {
        webTestClient
                .get()
                .uri("/softphone/")
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.TEMPORARY_REDIRECT)
                .expectHeader()
                .location("/softphone/index.html");
    }

    @Test
    @DisplayName("GET /softphone/index.html returns 200 OK and text/html content")
    void getSoftphoneIndexHtml_returnsOkAndHtmlContent() {
        webTestClient
                .get()
                .uri("/softphone/index.html")
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .contentTypeCompatibleWith(MediaType.TEXT_HTML)
                .expectBody(String.class)
                .value(body -> assertThat(body).contains("VoicePulse Softphone"));
    }

    @Test
    @DisplayName("GET /softphone/style.css returns 200 OK and text/css content")
    void getStyleCss_returnsOkAndCssContent() {
        webTestClient
                .get()
                .uri("/softphone/style.css")
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .contentTypeCompatibleWith(MediaType.valueOf("text/css"))
                .expectBody(String.class)
                .value(body -> assertThat(body).contains("--vp-"));
    }

    @Test
    @DisplayName("GET /softphone/softphone.js returns 200 OK and JavaScript content")
    void getSoftphoneJs_returnsOkAndJsContent() {
        webTestClient
                .get()
                .uri("/softphone/softphone.js")
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .contentTypeCompatibleWith(MediaType.valueOf("text/javascript"))
                .expectBody(String.class)
                .value(body -> assertThat(body).contains("VoicePulseSoftphone"));
    }

    @Test
    @DisplayName("GET /softphone/audio-processor.worklet.js returns 200 OK and JavaScript content")
    void getAudioProcessorWorkletJs_returnsOkAndJsContent() {
        webTestClient
                .get()
                .uri("/softphone/audio-processor.worklet.js")
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .contentTypeCompatibleWith(MediaType.valueOf("text/javascript"))
                .expectBody(String.class)
                .value(body -> assertThat(body).contains("LinearPcm16DownsamplerProcessor"));
    }
}
