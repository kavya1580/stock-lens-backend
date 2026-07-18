package com.stockdashboard.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockdashboard.dto.AiAnalysisResponse;
import com.stockdashboard.dto.AiChatMessage;
import com.stockdashboard.dto.AiChatResponse;
import com.stockdashboard.dto.FundamentalsResponse;
import com.stockdashboard.dto.LatestSnapshot;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Calls Google's Gemini generateContent API to turn a stock's fundamentals +
 * latest technical snapshot into a structured analysis, and to power a
 * follow-up chat that keeps seeing the same context on every turn.
 */
@Service
public class GeminiService {

    private static final Map<String, Object> ANALYSIS_RESPONSE_SCHEMA = Map.of(
            "type", "OBJECT",
            "properties", Map.of(
                    "verdict", Map.of("type", "STRING"),
                    "overallOpinion", Map.of("type", "STRING"),
                    "businessQuality", Map.of("type", "STRING"),
                    "risks", Map.of("type", "STRING"),
                    "competitiveAdvantage", Map.of("type", "STRING"),
                    "earningsSummary", Map.of("type", "STRING")
            ),
            "required", List.of(
                    "verdict", "overallOpinion", "businessQuality",
                    "risks", "competitiveAdvantage", "earningsSummary"
            )
    );

    private final ObjectMapper objectMapper;
    private final WebClient webClient;

    @Value("${app.gemini.api-key}")
    private String apiKey;

    @Value("${app.gemini.base-url}")
    private String baseUrl;

    @Value("${app.gemini.model}")
    private String model;

    public GeminiService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.webClient = WebClient.builder()
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    @Cacheable(value = "geminiAnalysis", key = "#fundamentals.symbol()")
    public AiAnalysisResponse analyze(FundamentalsResponse fundamentals, LatestSnapshot latest) {
        String systemInstruction = buildContextPrompt(fundamentals, latest) +
                "\n\nBased on the fundamentals and technicals above, produce a structured equity research " +
                "summary with exactly these fields: verdict (one of Bullish, Neutral, Cautious, Bearish), " +
                "overallOpinion, businessQuality, risks, competitiveAdvantage, earningsSummary. Each text " +
                "field should be 2-4 sentences, specific to this company's actual numbers, not generic.";

        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("systemInstruction", part(systemInstruction));
        requestBody.put("contents", List.of(turn("user", "Generate the structured analysis now.")));
        requestBody.put("generationConfig", Map.of(
                "responseMimeType", "application/json",
                "responseSchema", ANALYSIS_RESPONSE_SCHEMA
        ));

        String text = callGemini(requestBody);
        try {
            return objectMapper.readValue(text, AiAnalysisResponse.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse Gemini analysis response", e);
        }
    }

    public AiChatResponse chat(FundamentalsResponse fundamentals, LatestSnapshot latest,
                                List<AiChatMessage> history, String message) {
        String systemInstruction = buildContextPrompt(fundamentals, latest) +
                "\n\nYou are a knowledgeable equity research assistant chatting with an investor about this " +
                "specific stock. Answer using the fundamentals and technicals above plus general market " +
                "knowledge. Be direct and concise, and say so plainly when the data above doesn't cover " +
                "something the investor asked about.";

        List<Map<String, Object>> contents = new ArrayList<>();
        if (history != null) {
            for (AiChatMessage historyMessage : history) {
                contents.add(turn(historyMessage.role(), historyMessage.content()));
            }
        }
        contents.add(turn("user", message));

        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("systemInstruction", part(systemInstruction));
        requestBody.put("contents", contents);

        return new AiChatResponse(callGemini(requestBody));
    }

    private String buildContextPrompt(FundamentalsResponse fundamentals, LatestSnapshot latest) {
        try {
            return "Company fundamentals (JSON):\n" + objectMapper.writeValueAsString(fundamentals) +
                    "\n\nLatest technical indicators (JSON):\n" + objectMapper.writeValueAsString(latest);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize stock data for Gemini prompt", e);
        }
    }

    private Map<String, Object> part(String text) {
        return Map.of("parts", List.of(Map.of("text", text)));
    }

    private Map<String, Object> turn(String role, String text) {
        return Map.of("role", role, "parts", List.of(Map.of("text", text)));
    }

    private String callGemini(Map<String, Object> requestBody) {
        String uri = baseUrl + "/" + model + ":generateContent?key=" + apiKey;

        String payload = webClient.post()
                .uri(uri)
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(String.class)
                .block(Duration.ofSeconds(30));

        if (payload == null || payload.isBlank()) {
            throw new RuntimeException("Gemini API returned an empty response");
        }

        try {
            JsonNode root = objectMapper.readTree(payload);
            JsonNode textNode = root.path("candidates").path(0).path("content").path("parts").path(0).path("text");
            if (textNode.isMissingNode() || textNode.isNull()) {
                throw new RuntimeException("Gemini API response had no candidate text: " + payload);
            }
            return textNode.asText();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse Gemini API response", e);
        }
    }
}
