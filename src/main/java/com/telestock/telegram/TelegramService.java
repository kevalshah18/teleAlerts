package com.telestock.telegram;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class TelegramService {
    private final RestClient restClient = RestClient.create();

    @Value("${telegram.bot.token:}")
    private String token;

    @Value("${telegram.chat.id:}")
    private String chatId;

    public boolean isConfigured() {
        return token != null && !token.trim().isEmpty() && chatId != null && !chatId.trim().isEmpty();
    }

    public void sendMessage(String message) {
        if (!isConfigured()) {
            log.warn("Telegram credentials not configured. Skipping alert: {}", message);
            return;
        }

        String url = String.format("https://api.telegram.org/bot%s/sendMessage", token);
        
        try {
            restClient.post()
                    .uri(url)
                    .body(Map.of("chat_id", chatId, "text", message, "parse_mode", "Markdown"))
                    .retrieve()
                    .toBodilessEntity();
            log.info("Telegram alert sent successfully");
        } catch (Exception e) {
            log.error("Failed to send Telegram message", e);
        }
    }

    public String sendTestAlert() {
        if (!isConfigured()) {
            return "ERROR: TELEGRAM_BOT_TOKEN or TELEGRAM_CHAT_ID is missing in Render Environment Variables!";
        }

        String timeStr = ZonedDateTime.now(java.time.ZoneId.of("Asia/Kolkata"))
                .format(DateTimeFormatter.ofPattern("dd-MMM-yyyy HH:mm:ss z"));
        String testMsg = String.format("🔔 *TeleStock Integration Test*\n\n" +
                "✅ Your Telegram bot is *ONLINE* and connected successfully!\n" +
                "🕒 Time: `%s`\n" +
                "📊 Trading Hours: 9:15 AM - 3:30 PM IST (Mon-Fri)\n" +
                "You will receive instant alerts whenever trades or AI vetos trigger.", timeStr);

        try {
            String url = String.format("https://api.telegram.org/bot%s/sendMessage", token);
            restClient.post()
                    .uri(url)
                    .body(Map.of("chat_id", chatId, "text", testMsg, "parse_mode", "Markdown"))
                    .retrieve()
                    .toBodilessEntity();
            return "SUCCESS";
        } catch (Exception e) {
            log.error("Telegram test message failed", e);
            return "Failed to send to Telegram: " + e.getMessage();
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        if (isConfigured()) {
            log.info("Telegram configured. Sending startup notification...");
            sendMessage("🚀 *TeleStock System Started*\nAutonomous AI paper-trading engine is now LIVE and monitoring the market.");
        } else {
            log.warn("Telegram is NOT configured. Alerts will be skipped until TELEGRAM_BOT_TOKEN and TELEGRAM_CHAT_ID are provided.");
        }
    }
}
