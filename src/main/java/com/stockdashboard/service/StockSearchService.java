package com.stockdashboard.service;

import com.stockdashboard.dto.StockSearchResult;
import jakarta.annotation.PostConstruct;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Service
public class StockSearchService {

    private final List<StockSearchResult> symbols = new ArrayList<>();

    @PostConstruct
    public void loadSymbols() {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new ClassPathResource("nse_symbols.csv").getInputStream(), StandardCharsets.UTF_8))) {

            String line = reader.readLine(); // skip header
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] parts = line.split(",", 3);
                if (parts.length < 3) continue;
                symbols.add(new StockSearchResult(parts[0].trim(), parts[1].trim(), parts[2].trim()));
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load nse_symbols.csv", e);
        }
    }

    /**
     * Matches on symbol prefix or substring-in-name, case-insensitive.
     * Symbol-prefix matches are ranked first since the user is usually typing a ticker.
     */
    public List<StockSearchResult> search(String query, int limit) {
        String q = query.trim().toUpperCase();
        if (q.isEmpty()) return List.of();

        List<StockSearchResult> symbolMatches = new ArrayList<>();
        List<StockSearchResult> nameMatches = new ArrayList<>();

        for (StockSearchResult s : symbols) {
            if (s.symbol().startsWith(q)) {
                symbolMatches.add(s);
            } else if (s.name().toUpperCase().contains(q)) {
                nameMatches.add(s);
            }
        }

        symbolMatches.addAll(nameMatches);
        return symbolMatches.size() > limit ? symbolMatches.subList(0, limit) : symbolMatches;
    }
}
