package com.voicepulse.conversation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class ConversationEngineApplicationTests {

    @Test
    @DisplayName("conversation engine application context loads cleanly")
    void contextLoads() {}
}
