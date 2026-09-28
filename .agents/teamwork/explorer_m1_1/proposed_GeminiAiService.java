package com.telestock.ai;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.telestock.telegram.TelegramService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
@RequiredArgsConstructor
@Slf4j
public class GeminiAiService {
    private final TelegramService telegramService;
    private final RestClient restClient = RestClient.create();
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${gemini.api.key:}")
    private String apiKey;

    @Value("${gemini.api.model:gemini-2.5-flash}")
    private String model;

    public GeminiDecision evaluateSignal(String symbol, double price, String type) {
        if (apiKey == null || apiKey.isEmpty()) {
            log.warn("Gemini API Key missing, defaulting to approved");
            return new GeminiDecision(true, 0.0, "API key missing, auto-approved.");
        }
        
        String url = String.format("https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent?key=%s", model, apiKey);
        String prompt = String.format("Analyze the stock %s which just gave a technical %s signal at %.2f. " +
                "Evaluate live market context and sentiment. Return ONLY a JSON object with properties: " +
                "sentiment_score (number between -1.0 and 1.0), approval (boolean), reasoning (1 sentence string).", symbol, type, price);
        
        try {
            String requestBody = "{\"contents\":[{\"parts\":[{\"text\":\"" + prompt.replace("\"", "\\\"") + "\"}]}]}";
            String response = restClient.post()
                    .uri(url)
                    .header("Content-Type", "application/json")
                    .body(requestBody)
                    .retrieve()
                    .body(String.class);
            
            String jsonPart = response.substring(response.indexOf("{", response.indexOf("\"text\":") + 7));
            jsonPart = jsonPart.substring(0, jsonPart.lastIndexOf("}") + 1);
            
            jsonPart = jsonPart.replace("\\n", "").replace("\\\"", "\"").replaceAll("`json", "").replaceAll("`", "").trim();
            if (jsonPart.startsWith("\"") && jsonPart.endsWith("\"")) {
                jsonPart = jsonPart.substring(1, jsonPart.length() - 1);
            }
            if (jsonPart.contains("sentiment_score")) {
                int startIdx = jsonPart.indexOf("{");
                int endIdx = jsonPart.lastIndexOf("}") + 1;
                if(startIdx >= 0 && endIdx > startIdx) {
                   jsonPart = jsonPart.substring(startIdx, endIdx);
                }
            }

            GeminiDecision decision = mapper.readValue(jsonPart, GeminiDecision.class);
            
            if (!decision.isApproval() || decision.getSentimentScore() < -0.2) {
                decision.setApproval(false);
                telegramService.sendMessage("🚫 *AI VETO* for " + symbol + " " + type + "\nReasoning: " + decision.getReasoning() + "\nSentiment: " + decision.getSentimentScore());
            }
            
            return decision;
        } catch (Exception e) {
            log.error("Gemini API call failed", e);
            return new GeminiDecision(false, 0.0, "AI check failed: " + e.getMessage());
        }
    }
    
    @Data
    public static class GeminiDecision {
        @JsonProperty("approval")
        private boolean approval;
        @JsonProperty("sentiment_score")
        private double sentimentScore;
        @JsonProperty("reasoning")
        private String reasoning;
        
        public GeminiDecision() {}
        public GeminiDecision(boolean approval, double sentimentScore, String reasoning) {
            this.approval = approval;
            this.sentimentScore = sentimentScore;
            this.reasoning = reasoning;
        }
    }
}
