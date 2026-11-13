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

    @Value("${gemini.api.model:gemini-3.8-flash}")
    private String model;

    private volatile ModelEndpoint workingEndpoint = null;
    private boolean warningSent = false;

    public record ModelEndpoint(String apiVersion, String model) {}

    public String getWorkingEndpointInfo() {
        if (workingEndpoint != null) {
            return workingEndpoint.apiVersion() + "/" + workingEndpoint.model();
        }
        return (model != null && !model.isBlank()) ? "v1/" + model : "v1/gemini-1.5-flash";
    }

    public GeminiDecision evaluateSignal(String symbol, double price, String type) {
        if (apiKey == null || apiKey.trim().isEmpty()) {
            log.warn("Gemini API Key missing, defaulting to approved");
            return new GeminiDecision(true, 0.0, "API key missing, auto-approved.");
        }

        String prompt = String.format("Analyze the stock %s which just gave a technical %s signal at %.2f. " +
                "Evaluate live market context and sentiment. Return ONLY a JSON object with properties: " +
                "sentiment_score (number between -1.0 and 1.0), approval (boolean), reasoning (1 sentence string).", symbol, type, price);

        String response = null;
        ModelEndpoint successfulEndpoint = null;
        Exception lastException = null;

        // Try candidate endpoints in priority order
        List<ModelEndpoint> candidates = buildCandidateEndpoints();
        for (ModelEndpoint ep : candidates) {
            try {
                response = sendGenerateContentRequest(ep, prompt);
                successfulEndpoint = ep;
                break;
            } catch (Exception e) {
                lastException = e;
                String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
                if (msg.contains("404") || msg.contains("not found")) {
                    log.debug("Gemini endpoint {}:{} returned 404, trying next candidate...", ep.apiVersion(), ep.model());
                    continue;
                }
                // If it's an authorization or bad request error, trying other models with same key will also fail
                if (msg.contains("401") || msg.contains("403") || msg.contains("api_key_invalid") || msg.contains("permission_denied")) {
                    break;
                }
            }
        }

        // If Google suggested a specific model in the error message (e.g. models/gemini-3.8-flash), try it immediately!
        if (response == null && lastException != null) {
            String rawMsg = lastException.getMessage() != null ? lastException.getMessage() : "";
            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("models/([a-zA-Z0-9.-]+)").matcher(rawMsg);
            while (matcher.find()) {
                String suggested = matcher.group(1);
                log.info("Detected Google suggested model in error message: '{}'. Testing immediately...", suggested);
                for (String ver : new String[]{"v1beta", "v1"}) {
                    try {
                        ModelEndpoint ep = new ModelEndpoint(ver, suggested);
                        response = sendGenerateContentRequest(ep, prompt);
                        successfulEndpoint = ep;
                        break;
                    } catch (Exception ex) {
                        lastException = ex;
                    }
                }
                if (response != null) break;
            }
        }

        // If static candidates failed due to 404, perform dynamic discovery across v1 and v1beta
        if (response == null && lastException != null) {
            String msg = lastException.getMessage() != null ? lastException.getMessage().toLowerCase() : "";
            if (msg.contains("404") || msg.contains("not found")) {
                log.info("Attempting dynamic Gemini model discovery via ListModels...");
                ModelEndpoint discovered = autoDiscoverModel();
                if (discovered != null) {
                    try {
                        response = sendGenerateContentRequest(discovered, prompt);
                        successfulEndpoint = discovered;
                    } catch (Exception e) {
                        lastException = e;
                    }
                }
            }
        }

        try {
            if (response == null) {
                throw lastException != null ? lastException : new RuntimeException("No suitable Gemini model found");
            }

            if (successfulEndpoint != null && !successfulEndpoint.equals(workingEndpoint)) {
                this.workingEndpoint = successfulEndpoint;
                log.info("Successfully established active Gemini model: {} on API version: {}", 
                        successfulEndpoint.model(), successfulEndpoint.apiVersion());
            }

            GeminiDecision decision = parseGeminiResponse(response);

            if (!decision.isApproval() || decision.getSentimentScore() < -0.2) {
                decision.setApproval(false);
                telegramService.sendMessage("🚫 *AI VETO* for " + symbol + " " + type + "\nReasoning: " + decision.getReasoning() + "\nSentiment: " + decision.getSentimentScore());
            }

            return decision;
        } catch (Exception e) {
            log.error("Gemini API call failed: {}", e.getMessage());
            String cleanErr = extractCleanErrorMessage(e);
            if (!warningSent) {
                telegramService.sendMessage("⚠️ *Gemini AI Notice*: Gemini API returned an error (" + cleanErr + "). Gracefully bypassing AI veto to ensure technical trades continue executing!");
                warningSent = true;
            }
            // Graceful fallback: Do NOT freeze trading if Google AI is unavailable or the key has an issue!
            return new GeminiDecision(true, 0.0, "AI bypassed due to API error: " + cleanErr);
        }
    }

    private List<ModelEndpoint> buildCandidateEndpoints() {
        List<ModelEndpoint> candidates = new ArrayList<>();

        // 1. If we already established a working endpoint, use it first
        if (workingEndpoint != null) {
            candidates.add(workingEndpoint);
        }

        // 2. Google's current default for new API keys (Gemini 3.8 Flash)
        candidates.add(new ModelEndpoint("v1beta", "gemini-3.8-flash"));
        candidates.add(new ModelEndpoint("v1", "gemini-3.8-flash"));

        // 3. If user configured a specific model via property/environment variable
        if (model != null && !model.isBlank()) {
            String clean = model.trim().startsWith("models/") ? model.trim().substring(7) : model.trim();
            candidates.add(new ModelEndpoint("v1beta", clean));
            candidates.add(new ModelEndpoint("v1", clean));
        }

        // 4. Other official stable production and beta models
        candidates.add(new ModelEndpoint("v1beta", "gemini-2.5-flash"));
        candidates.add(new ModelEndpoint("v1beta", "gemini-2.0-flash"));
        candidates.add(new ModelEndpoint("v1", "gemini-1.5-flash"));
        candidates.add(new ModelEndpoint("v1", "gemini-1.5-pro"));
        candidates.add(new ModelEndpoint("v1beta", "gemini-1.5-flash-latest"));
        candidates.add(new ModelEndpoint("v1beta", "gemini-2.0-flash-exp"));
        candidates.add(new ModelEndpoint("v1beta", "gemini-1.5-flash"));

        return candidates.stream().distinct().toList();
    }

    private String sendGenerateContentRequest(ModelEndpoint endpoint, String prompt) {
        String cleanModel = endpoint.model().startsWith("models/") ? endpoint.model().substring(7) : endpoint.model();
        String url = String.format("https://generativelanguage.googleapis.com/%s/models/%s:generateContent?key=%s", 
                endpoint.apiVersion(), cleanModel, apiKey.trim());
        String requestBody = "{\"contents\":[{\"parts\":[{\"text\":\"" + prompt.replace("\"", "\\\"") + "\"}]}]}";

        return restClient.post()
                .uri(url)
                .header("Content-Type", "application/json")
                .header("x-goog-api-key", apiKey.trim())
                .body(requestBody)
                .retrieve()
                .body(String.class);
    }

    private synchronized ModelEndpoint autoDiscoverModel() {
        if (apiKey == null || apiKey.trim().isEmpty()) {
            return null;
        }

        String[] versions = new String[]{"v1", "v1beta"};
        for (String ver : versions) {
            try {
                String listUrl = String.format("https://generativelanguage.googleapis.com/%s/models?key=%s", ver, apiKey.trim());
                String response = restClient.get()
                        .uri(listUrl)
                        .header("x-goog-api-key", apiKey.trim())
                        .retrieve()
                        .body(String.class);

                if (response != null) {
                    JsonNode root = mapper.readTree(response);
                    JsonNode modelsNode = root.path("models");
                    if (modelsNode.isArray() && !modelsNode.isEmpty()) {
                        List<String> candidates = new ArrayList<>();
                        for (JsonNode m : modelsNode) {
                            JsonNode methods = m.path("supportedGenerationMethods");
                            boolean supportsGenerate = false;
                            if (methods.isArray()) {
                                for (JsonNode method : methods) {
                                    if ("generateContent".equalsIgnoreCase(method.asText())) {
                                        supportsGenerate = true;
                                        break;
                                    }
                                }
                            }
                            if (supportsGenerate) {
                                String name = m.path("name").asText("");
                                candidates.add(name.startsWith("models/") ? name.substring(7) : name);
                            }
                        }

                        // Prioritize: 3.8-flash -> any flash -> any pro -> any candidate
                        Optional<String> chosen = candidates.stream().filter(c -> c.contains("3.8-flash")).findFirst();
                        if (chosen.isEmpty()) {
                            chosen = candidates.stream().filter(c -> c.contains("flash")).findFirst();
                        }
                        if (chosen.isEmpty()) {
                            chosen = candidates.stream().filter(c -> c.contains("pro")).findFirst();
                        }
                        if (chosen.isEmpty()) {
                            chosen = candidates.stream().findFirst();
                        }

                        if (chosen.isPresent()) {
                            log.info("Auto-discovered active Gemini model '{}' on API version '{}'", chosen.get(), ver);
                            return new ModelEndpoint(ver, chosen.get());
                        }
                    }
                }
            } catch (Exception e) {
                log.debug("Auto-discovery failed on {}: {}", ver, e.getMessage());
            }
        }

        return null;
    }

    private String extractCleanErrorMessage(Exception e) {
        if (e == null) return "Unknown error";
        String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        try {
            int start = msg.indexOf('{');
            int end = msg.lastIndexOf('}');
            if (start != -1 && end > start) {
                JsonNode errJson = mapper.readTree(msg.substring(start, end + 1));
                JsonNode messageNode = errJson.path("error").path("message");
                if (!messageNode.isMissingNode() && !messageNode.asText().isBlank()) {
                    String clean = messageNode.asText().replaceAll("\\s+", " ").trim();
                    return clean.length() > 250 ? clean.substring(0, 250) + "..." : clean;
                }
            }
        } catch (Exception ignored) {}

        msg = msg.replaceAll("\\r?\\n", " ").replaceAll("\\s+", " ").trim();
        return msg.length() > 200 ? msg.substring(0, 200) + "..." : msg;
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
