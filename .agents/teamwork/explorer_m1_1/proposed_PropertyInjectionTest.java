package com.telestock.config;

import com.telestock.ai.GeminiAiService;
import com.telestock.telegram.TelegramService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@TestPropertySource(properties = {
    "server.port=8080",
    "gemini.api.key=TEST_KEY_123",
    "gemini.api.model=gemini-test-model",
    "telegram.bot.token=TEST_BOT_TOKEN_XYZ",
    "telegram.chat.id=123456789"
})
class PropertyInjectionTest {

    @Autowired
    private GeminiAiService geminiAiService;

    @Autowired
    private TelegramService telegramService;

    @Test
    @DisplayName("Verify gemini properties are injected correctly into GeminiAiService")
    void testGeminiPropertiesInjected() {
        assertNotNull(geminiAiService, "GeminiAiService bean must not be null");
        
        String apiKey = (String) ReflectionTestUtils.getField(geminiAiService, "apiKey");
        String model = (String) ReflectionTestUtils.getField(geminiAiService, "model");

        assertEquals("TEST_KEY_123", apiKey, "apiKey must match injected property value");
        assertEquals("gemini-test-model", model, "model must match injected property value");
    }

    @Test
    @DisplayName("Verify telegram properties are injected correctly into TelegramService")
    void testTelegramPropertiesInjected() {
        assertNotNull(telegramService, "TelegramService bean must not be null");
        
        String token = (String) ReflectionTestUtils.getField(telegramService, "token");
        String chatId = (String) ReflectionTestUtils.getField(telegramService, "chatId");

        assertEquals("TEST_BOT_TOKEN_XYZ", token, "token must match injected property value");
        assertEquals("123456789", chatId, "chatId must match injected property value");
    }

    @Test
    @DisplayName("Verify GeminiAiService auto-approves when apiKey is empty/unset")
    void testGeminiAutoApproveWhenKeyEmpty() {
        TelegramService mockTg = org.mockito.Mockito.mock(TelegramService.class);
        GeminiAiService service = new GeminiAiService(mockTg);
        ReflectionTestUtils.setField(service, "apiKey", "");
        ReflectionTestUtils.setField(service, "model", "gemini-2.5-flash");

        GeminiAiService.GeminiDecision decision = service.evaluateSignal("RELIANCE.NS", 2500.0, "BUY");
        assertNotNull(decision);
        assertTrue(decision.isApproval(), "Should auto-approve when API key is empty");
        assertEquals("API key missing, auto-approved.", decision.getReasoning());
    }

    @Test
    @DisplayName("Verify TelegramService safely skips sending when credentials are empty")
    void testTelegramSkipWhenCredentialsEmpty() {
        TelegramService service = new TelegramService();
        ReflectionTestUtils.setField(service, "token", "");
        ReflectionTestUtils.setField(service, "chatId", "");

        // Must execute cleanly without exception
        assertDoesNotThrow(() -> service.sendMessage("Test alert"));
    }
}
