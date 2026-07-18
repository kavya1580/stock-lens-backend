package com.stockdashboard.service;

import com.stockdashboard.dto.StockSearchResult;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

@Service
public class StockSearchService {

    private static final Logger log = LoggerFactory.getLogger(StockSearchService.class);

    /** Below this row count, treat an NSE fetch as bad data (blocked/HTML/truncated) and keep the old list. */
    private static final int MIN_EXPECTED_SYMBOLS = 500;

    private final WebClient nseWebClient;

    @Value("${app.symbols.source-url}")
    private String sourceUrl;

    @Value("${app.symbols.file-path}")
    private String filePath;

    private volatile List<StockSearchResult> symbols = List.of();

    public StockSearchService(WebClient nseWebClient) {
        this.nseWebClient = nseWebClient;
    }

    @PostConstruct
    public void init() {
        Path path = Paths.get(filePath);
        try {
            if (!Files.exists(path)) {
                seedFromClasspath(path);
            }
            symbols = loadFromFile(path);
            log.info("Loaded {} symbols from {}", symbols.size(), path.toAbsolutePath());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load NSE symbol list from " + path, e);
        }

        Thread refreshThread = new Thread(this::refreshFromNse, "nse-symbol-refresh-startup");
        refreshThread.setDaemon(true);
        refreshThread.start();
    }

    /**
     * Matches on symbol prefix or substring-in-name, case-insensitive.
     * Symbol-prefix matches are ranked first since the user is usually typing a ticker.
     */
    public List<StockSearchResult> search(String query, int limit) {
        String q = query.trim().toUpperCase();
        if (q.isEmpty()) return List.of();

        List<StockSearchResult> current = symbols;
        List<StockSearchResult> symbolMatches = new ArrayList<>();
        List<StockSearchResult> nameMatches = new ArrayList<>();

        for (StockSearchResult s : current) {
            if (s.symbol().startsWith(q)) {
                symbolMatches.add(s);
            } else if (s.name().toUpperCase().contains(q)) {
                nameMatches.add(s);
            }
        }

        symbolMatches.addAll(nameMatches);
        return symbolMatches.size() > limit ? symbolMatches.subList(0, limit) : symbolMatches;
    }

    /**
     * Pulls NSE's official equity list nightly and swaps it into memory. Runs once more at
     * startup (from init()) so a fresh checkout doesn't wait up to a day for real data.
     */
    @Scheduled(cron = "${app.symbols.refresh-cron:0 0 2 * * *}", zone = "Asia/Kolkata")
    public void refreshFromNse() {
        try {
            String raw = nseWebClient.get()
                    .uri(sourceUrl)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            List<StockSearchResult> parsed = parseNseCsv(raw);
            if (parsed.size() < MIN_EXPECTED_SYMBOLS) {
                log.warn("NSE symbol refresh returned only {} rows (expected {}+); keeping previous list of {}",
                        parsed.size(), MIN_EXPECTED_SYMBOLS, symbols.size());
                return;
            }

            writeAtomic(Paths.get(filePath), parsed);
            symbols = parsed;
            log.info("Refreshed NSE symbol list: {} symbols", parsed.size());
        } catch (Exception e) {
            log.warn("NSE symbol refresh failed, keeping previous list of {} symbols: {}",
                    symbols.size(), e.getMessage());
        }
    }

    private void seedFromClasspath(Path path) throws IOException {
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (InputStream in = new ClassPathResource("nse_symbols.csv").getInputStream()) {
            Files.copy(in, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private List<StockSearchResult> loadFromFile(Path path) throws IOException {
        List<StockSearchResult> result = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line = reader.readLine(); // skip header
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] parts = line.split(",", 3);
                if (parts.length < 3) continue;
                result.add(new StockSearchResult(parts[0].trim(), parts[1].trim(), parts[2].trim()));
            }
        }
        return result;
    }

    /** NSE's columns are SYMBOL, NAME OF COMPANY, SERIES, DATE OF LISTING, ...; we only keep the first three. */
    private List<StockSearchResult> parseNseCsv(String raw) {
        List<StockSearchResult> result = new ArrayList<>();
        if (raw == null) return result;

        String[] lines = raw.split("\r?\n");
        for (int i = 1; i < lines.length; i++) { // skip header
            String line = lines[i];
            if (line.isBlank()) continue;
            String[] parts = line.split(",");
            if (parts.length < 3) continue;
            result.add(new StockSearchResult(parts[0].trim(), parts[1].trim(), parts[2].trim()));
        }
        return result;
    }

    private void writeAtomic(Path path, List<StockSearchResult> list) throws IOException {
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");

        StringBuilder sb = new StringBuilder("SYMBOL,NAME,SERIES\n");
        for (StockSearchResult s : list) {
            sb.append(s.symbol()).append(',').append(s.name()).append(',').append(s.series()).append('\n');
        }
        Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8);
        Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
