package com.telestock.ai;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.telestock.telegram.TelegramService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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

    private volatile String activeModel;
    private boolean warningSent = false;

    public GeminiDecision evaluateSignal(String symbol, double price, String type) {
        if (apiKey == null || apiKey.trim().isEmpty()) {
            log.warn("Gemini API Key missing, defaulting to approved");
            return new GeminiDecision(true, 0.0, "API key missing, auto-approved.");
        }

        String prompt = String.format("Analyze the stock %s which just gave a technical %s signal at %.2f. " +
                "Evaluate live market context and sentiment. Return ONLY a JSON object with properties: " +
                "sentiment_score (number between -1.0 and 1.0), approval (boolean), reasoning (1 sentence string).", symbol, type, price);

        String targetModel = getActiveOrConfiguredModel();

        try {
            String response;
            try {
                response = sendGenerateContentRequest(targetModel, prompt);
            } catch (Exception e) {
                // If 404 Not Found (e.g. model deprecated or renamed), attempt automatic model discovery and retry
                String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
                if (msg.contains("404") || msg.contains("not found")) {
                    log.warn("Model '{}' returned 404 Not Found. Attempting auto-discovery of available Gemini models...", targetModel);
                    String discovered = autoDiscoverModel();
                    if (discovered != null && !discovered.equalsIgnoreCase(targetModel)) {
                        log.info("Retrying generateContent with auto-discovered model: '{}'", discovered);
                        response = sendGenerateContentRequest(discovered, prompt);
                        targetModel = discovered;
                    } else {
                        throw e;
                    }
                } else {
                    throw e;
                }
            }

            GeminiDecision decision = parseGeminiResponse(response);

            if (!decision.isApproval() || decision.getSentimentScore() < -0.2) {
                decision.setApproval(false);
                telegramService.sendMessage("🚫 *AI VETO* for " + symbol + " " + type + "\nReasoning: " + decision.getReasoning() + "\nSentiment: " + decision.getSentimentScore());
            }

            return decision;
        } catch (Exception e) {
            log.error("Gemini API call failed: {}", e.getMessage());
            String errDetail = e.getMessage() != null ? e.getMessage() : "Unknown error";
            if (errDetail.length() > 150) {
                errDetail = errDetail.substring(0, 150) + "...";
            }
            if (!warningSent) {
                telegramService.sendMessage("⚠️ *Gemini AI Notice*: Gemini API returned an error (" + errDetail + "). Gracefully bypassing AI veto to ensure technical trades continue executing!");
                warningSent = true;
            }
            // Graceful fallback: Do NOT freeze trading if Google AI is unavailable or the key has an issue!
            return new GeminiDecision(true, 0.0, "AI bypassed due to API error: " + errDetail);
        }
    }

    private String getActiveOrConfiguredModel() {
        if (activeModel != null && !activeModel.isBlank()) {
            return activeModel;
        }
        return (model != null && !model.isBlank()) ? model.trim() : "gemini-2.5-flash";
    }

    private String sendGenerateContentRequest(String modelName, String prompt) {
        String cleanModel = modelName.startsWith("models/") ? modelName.substring(7) : modelName;
        String url = String.format("https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent?key=%s", cleanModel, apiKey.trim());
        String requestBody = "{\"contents\":[{\"parts\":[{\"text\":\"" + prompt.replace("\"", "\\\"") + "\"}]}]}";

        return restClient.post()
                .uri(url)
                .header("Content-Type", "application/json")
                .body(requestBody)
                .retrieve()
                .body(String.class);
    }

    private synchronized String autoDiscoverModel() {
        if (apiKey == null || apiKey.trim().isEmpty()) {
            return null;
        }

        try {
            String listUrl = String.format("https://generativelanguage.googleapis.com/v1beta/models?key=%s", apiKey.trim());
            String response = restClient.get()
                    .uri(listUrl)
                    .retrieve()
                    .body(String.class);

            if (response != null) {
                JsonNode root = mapper.readTree(response);
                JsonNode modelsNode = root.path("models");
                if (modelsNode.isArray()) {
                    List<String> candidates = new ArrayList<>();
                    for (JsonNode m : modelsNode) {
                        String name = m.path("name").asText(""); // e.g. "models/gemini-2.5-flash"
                        boolean supportsGenerate = false;
                        JsonNode methods = m.path("supportedGenerationMethods");
                        if (methods.isArray()) {
                            for (JsonNode method : methods) {
                                if ("generateContent".equalsIgnoreCase(method.asText())) {
                                    supportsGenerate = true;
                                    break;
                                }
                            }
                        }
                        if (supportsGenerate) {
                            String cleanName = name.startsWith("models/") ? name.substring(7) : name;
                            candidates.add(cleanName);
                        }
                    }

                    // Prioritize: gemini-2.5-flash -> gemini-2.0-flash -> any flash -> any pro -> first available
                    Optional<String> chosen = candidates.stream().filter(c -> c.equalsIgnoreCase("gemini-2.5-flash")).findFirst();
                    if (chosen.isEmpty()) {
                        chosen = candidates.stream().filter(c -> c.contains("2.5-flash")).findFirst();
                    }
                    if (chosen.isEmpty()) {
                        chosen = candidates.stream().filter(c -> c.equalsIgnoreCase("gemini-2.0-flash")).findFirst();
                    }
                    if (chosen.isEmpty()) {
                        chosen = candidates.stream().filter(c -> c.contains("2.0-flash")).findFirst();
                    }
                    if (chosen.isEmpty()) {
                        chosen = candidates.stream().filter(c -> c.contains("flash")).findFirst();
                    }
                    if (chosen.isEmpty()) {
                        chosen = candidates.stream().findFirst();
                    }

                    if (chosen.isPresent()) {
                        this.activeModel = chosen.get();
                        log.info("Auto-discovered active Gemini model: '{}'", this.activeModel);
                        return this.activeModel;
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to query Gemini model list: {}", e.getMessage());
        }

        return null;
    }

    private GeminiDecision parseGeminiResponse(String response) throws Exception {
        JsonNode root = mapper.readTree(response);
        JsonNode candidates = root.path("candidates");
        if (candidates.isArray() && !candidates.isEmpty()) {
            JsonNode textNode = candidates.get(0).path("content").path("parts").get(0).path("text");
            if (!textNode.isMissingNode()) {
                String rawText = textNode.asText("");
                int start = rawText.indexOf('{');
                int end = rawText.lastIndexOf('}');
                if (start != -1 && end > start) {
                    String jsonPart = rawText.substring(start, end + 1);
                    GeminiDecision decision = mapper.readValue(jsonPart, GeminiDecision.class);
                    if (decision.getReasoning() != null && decision.getReasoning().length() > 500) {
                        decision.setReasoning(decision.getReasoning().substring(0, 500));
                    }
                    return decision;
                }
            }
        }

        // Fallback to legacy string parsing if structure varies
        String jsonPart = response.substring(response.indexOf("{", response.indexOf("\"text\":") + 7));
        jsonPart = jsonPart.substring(0, jsonPart.lastIndexOf("}") + 1);
        jsonPart = jsonPart.replace("\\n", "").replace("\\\"", "\"").replaceAll("`json", "").replaceAll("`", "").trim();
        int startIdx = jsonPart.indexOf("{");
        int endIdx = jsonPart.lastIndexOf("}") + 1;
        if (startIdx >= 0 && endIdx > startIdx) {
            jsonPart = jsonPart.substring(startIdx, endIdx);
        }

        GeminiDecision decision = mapper.readValue(jsonPart, GeminiDecision.class);
        if (decision.getReasoning() != null && decision.getReasoning().length() > 500) {
            decision.setReasoning(decision.getReasoning().substring(0, 500));
        }
        return decision;
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
