package com.telestock.telegram;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class TelegramService {
    private final RestClient restClient = RestClient.create();

    @Value("")
    private String token;

    @Value("")
    private String chatId;

    public void sendMessage(String message) {
        if (token == null || token.isEmpty() || chatId == null || chatId.isEmpty()) {
            log.warn("Telegram credentials not set. Skipping alert: {}", message);
            return;
        }

        String url = String.format("https://api.telegram.org/bot%s/sendMessage", token);
        
        try {
            restClient.post()
                    .uri(url)
                    .body(Map.of("chat_id", chatId, "text", message, "parse_mode", "Markdown"))
                    .retrieve()
                    .toBodilessEntity();
            log.info("Telegram alert sent");
        } catch (Exception e) {
            log.error("Failed to send Telegram message", e);
        }
    }
}
