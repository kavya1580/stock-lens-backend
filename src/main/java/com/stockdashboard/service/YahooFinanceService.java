package com.stockdashboard.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockdashboard.dto.OhlcvBar;
import com.stockdashboard.exception.StockNotFoundException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@Service
public class YahooFinanceService {

    private final WebClient yahooWebClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${app.yahoo.base-url}")
    private String baseUrl;

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    public YahooFinanceService(WebClient yahooWebClient) {
        this.yahooWebClient = yahooWebClient;
    }

    /**
     * Fetches daily OHLCV candles for a symbol.
     *
     * @param symbol   bare NSE/BSE symbol, e.g. "RELIANCE" (no suffix)
     * @param exchange "NSE" or "BSE"
     * @param range    Yahoo range string, e.g. "6mo", "1y", "3mo"
     */
    public List<OhlcvBar> fetchDailyBars(String symbol, String exchange, String range) {
        String suffix = "BSE".equalsIgnoreCase(exchange) ? ".BO" : ".NS";
        String ticker = symbol.toUpperCase() + suffix;

        String url = baseUrl + "/" + ticker + "?interval=1d&range=" + range;

        String rawJson;
        try {
            rawJson = yahooWebClient.get()
                    .uri(url)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
        } catch (Exception e) {
            throw new StockNotFoundException(
                    "Could not reach Yahoo Finance for symbol '" + symbol + "': " + e.getMessage());
        }

        return parseBars(rawJson, symbol);
    }

    private List<OhlcvBar> parseBars(String rawJson, String symbol) {
        try {
            JsonNode root = objectMapper.readTree(rawJson);
            JsonNode resultArray = root.path("chart").path("result");

            if (!resultArray.isArray() || resultArray.isEmpty()) {
                String yahooError = root.path("chart").path("error").path("description").asText(null);
                throw new StockNotFoundException(
                        "No data found for symbol '" + symbol + "'."
                                + (yahooError != null ? " (" + yahooError + ")" : " Check the symbol and try again."));
            }

            JsonNode result = resultArray.get(0);
            JsonNode timestamps = result.path("timestamp");
            JsonNode quote = result.path("indicators").path("quote").get(0);

            JsonNode opens = quote.path("open");
            JsonNode highs = quote.path("high");
            JsonNode lows = quote.path("low");
            JsonNode closes = quote.path("close");
            JsonNode volumes = quote.path("volume");

            List<OhlcvBar> bars = new ArrayList<>();
            for (int i = 0; i < timestamps.size(); i++) {
                JsonNode closeNode = closes.get(i);
                // Skip bars with no trade data (common around holidays at the edges of the range)
                if (closeNode == null || closeNode.isNull()) {
                    continue;
                }

                long ts = timestamps.get(i).asLong();
                String date = Instant.ofEpochSecond(ts).atZone(IST).format(DATE_FMT);

                bars.add(new OhlcvBar(
                        ts,
                        date,
                        nullableDouble(opens, i),
                        nullableDouble(highs, i),
                        nullableDouble(lows, i),
                        nullableDouble(closes, i),
                        nullableLong(volumes, i)
                ));
            }

            if (bars.isEmpty()) {
                throw new StockNotFoundException("No trading data available for symbol '" + symbol + "'.");
            }
            return bars;

        } catch (StockNotFoundException e) {
            throw e;
        } catch (Exception e) {
            throw new StockNotFoundException("Could not parse data for symbol '" + symbol + "': " + e.getMessage());
        }
    }

    private Double nullableDouble(JsonNode arrayNode, int index) {
        if (arrayNode == null || index >= arrayNode.size()) return null;
        JsonNode node = arrayNode.get(index);
        return (node == null || node.isNull()) ? null : node.asDouble();
    }

    private Long nullableLong(JsonNode arrayNode, int index) {
        if (arrayNode == null || index >= arrayNode.size()) return null;
        JsonNode node = arrayNode.get(index);
        return (node == null || node.isNull()) ? null : node.asLong();
    }
}
