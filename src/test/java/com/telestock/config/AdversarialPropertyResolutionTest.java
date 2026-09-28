package com.telestock.config;

import com.telestock.ai.GeminiAiService;
import com.telestock.telegram.TelegramService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Adversarial edge-case tests for property resolution, credential fallbacks,
 * and fail-safe defaults in AI and Alerting components.
 */
class AdversarialPropertyResolutionTest {

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {""})
    @DisplayName("Edge Case: GeminiAiService must auto-approve and not crash when apiKey is null or empty")
    void testGeminiApiKeyNullOrEmptyEdgeCases(String apiKey) {
        TelegramService mockTg = mock(TelegramService.class);
        GeminiAiService service = new GeminiAiService(mockTg);
        ReflectionTestUtils.setField(service, "apiKey", apiKey);
        ReflectionTestUtils.setField(service, "model", "gemini-2.5-flash");

        GeminiAiService.GeminiDecision decision = service.evaluateSignal("RELIANCE.NS", 2500.0, "BUY");

        assertNotNull(decision, "Decision must not be null");
        assertTrue(decision.isApproval(), "Must auto-approve when API key is unset or empty");
        assertEquals("API key missing, auto-approved.", decision.getReasoning());
        assertEquals(0.0, decision.getSentimentScore());
        verifyNoInteractions(mockTg);
    }

    @Test
    @DisplayName("Edge Case: GeminiAiService with custom model and fallback")
    void testGeminiCustomModelResolution() {
        TelegramService mockTg = mock(TelegramService.class);
        GeminiAiService service = new GeminiAiService(mockTg);
        ReflectionTestUtils.setField(service, "apiKey", null);
        ReflectionTestUtils.setField(service, "model", "gemini-1.5-pro");

        String model = (String) ReflectionTestUtils.getField(service, "model");
        assertEquals("gemini-1.5-pro", model);

        // Still auto-approves because apiKey is null
        GeminiAiService.GeminiDecision decision = service.evaluateSignal("TCS.NS", 3500.0, "BUY");
        assertTrue(decision.isApproval());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @DisplayName("Edge Case: TelegramService skips safely when token is null or empty")
    void testTelegramTokenNullOrEmpty(String token) {
        TelegramService service = new TelegramService();
        ReflectionTestUtils.setField(service, "token", token);
        ReflectionTestUtils.setField(service, "chatId", "123456789");

        assertDoesNotThrow(() -> service.sendMessage("Test alert"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @DisplayName("Edge Case: TelegramService skips safely when chatId is null or empty")
    void testTelegramChatIdNullOrEmpty(String chatId) {
        TelegramService service = new TelegramService();
        ReflectionTestUtils.setField(service, "token", "VALID_TOKEN_123");
        ReflectionTestUtils.setField(service, "chatId", chatId);

        assertDoesNotThrow(() -> service.sendMessage("Test alert"));
    }

    @Test
    @DisplayName("Edge Case: TelegramService skips safely when both token and chatId are null")
    void testTelegramBothNull() {
        TelegramService service = new TelegramService();
        ReflectionTestUtils.setField(service, "token", null);
        ReflectionTestUtils.setField(service, "chatId", null);

        assertDoesNotThrow(() -> service.sendMessage("Test alert"));
    }

    @Test
    @DisplayName("Edge Case: TelegramService skips safely with null message")
    void testTelegramNullMessage() {
        TelegramService service = new TelegramService();
        ReflectionTestUtils.setField(service, "token", "");
        ReflectionTestUtils.setField(service, "chatId", "");

        assertDoesNotThrow(() -> service.sendMessage(null));
    }
}
