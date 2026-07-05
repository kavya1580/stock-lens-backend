package com.stockdashboard.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockdashboard.dto.AwardStockResponse;
import com.stockdashboard.dto.FundamentalScoreResponse;
import com.stockdashboard.dto.FundamentalsResponse;
import com.stockdashboard.dto.StockSearchResult;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class BseAwardStockService {

    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36";
    private static final String BSE_ANNOUNCEMENT_URL =
            "https://api.bseindia.com/BseIndiaAPI/api/AnnSubCategoryGetData/w";

    private static final Pattern COMPANY_FROM_PATTERN = Pattern.compile(
            "(?i)\\b(?:from|received from|received order from|order from|award from|awarded by|letter of award from|letter of intent from|loa from|loi from|purchase order from)\\s+([^\\.\\,\\;\\n]+)");
    private static final Pattern MONEY_PATTERN = Pattern.compile(
            "(?i)(?:Rs\\.?|₹|INR|USD)\\s*[0-9][0-9,]*(?:\\.[0-9]+)?(?:\\s*(?:Crores?|Crore|Cr|Lakhs?|Lacs?|L|Million|Billion|Thousand|Mn))?(?:\\s*\\([^)]*\\))?");
    private static final Pattern MONEY_WORD_PATTERN = Pattern.compile(
            "(?i)\\b[0-9][0-9,]*(?:\\.[0-9]+)?\\s*(?:Crores?|Crore|Cr|Lakhs?|Lacs?|L|Million|Billion|Thousand|Mn)\\b");
    private static final ExecutorService LOOKUP_EXECUTOR = Executors.newFixedThreadPool(
            Math.max(2, Math.min(6, Runtime.getRuntime().availableProcessors()))
    );
    private static final long ROW_LOOKUP_TIMEOUT_SECONDS = 4;

    private final ObjectMapper objectMapper;
    private final StockSearchService stockSearchService;
    private final ScreenerScraperService screenerScraperService;
    private final FundamentalScoreService fundamentalScoreService;
    private final WebClient webClient;

    public BseAwardStockService(
            ObjectMapper objectMapper,
            StockSearchService stockSearchService,
            ScreenerScraperService screenerScraperService,
            FundamentalScoreService fundamentalScoreService
    ) {
        this.objectMapper = objectMapper;
        this.stockSearchService = stockSearchService;
        this.screenerScraperService = screenerScraperService;
        this.fundamentalScoreService = fundamentalScoreService;
        this.webClient = WebClient.builder()
                .defaultHeader(HttpHeaders.USER_AGENT, USER_AGENT)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.REFERER, "https://www.bseindia.com/")
                .defaultHeader("X-Requested-With", "XMLHttpRequest")
                .build();
    }

    @Cacheable(value = "awardStocks", key = "#pageNo + '-' + #prevDate + '-' + #toDate + '-' + #search")
    public List<AwardStockResponse> fetchAwardStocks(int pageNo, String prevDate, String toDate, String search) {
        String payload = webClient.get()
                .uri(buildUri(pageNo, prevDate, toDate, search))
                .retrieve()
                .bodyToMono(String.class)
                .block(Duration.ofSeconds(30));

        if (payload == null || payload.isBlank()) {
            return List.of();
        }

        try {
            JsonNode root = objectMapper.readTree(payload);
            JsonNode table = root.path("Table");
            if (!table.isArray()) {
                return List.of();
            }

            List<JsonNode> rows = new ArrayList<>();
            table.forEach(rows::add);

            List<CompletableFuture<RowResult>> futures = new ArrayList<>();
            for (int i = 0; i < rows.size(); i++) {
                final int index = i;
                final JsonNode row = rows.get(i);
                AwardStockResponse fallback = toPartialResponse(row);
                futures.add(CompletableFuture
                        .supplyAsync(() -> new RowResult(index, toResponse(row)), LOOKUP_EXECUTOR)
                        .completeOnTimeout(new RowResult(index, fallback), ROW_LOOKUP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                        .exceptionally(ex -> new RowResult(index, fallback)));
            }

            return futures.stream()
                    .map(CompletableFuture::join)
                    .sorted(Comparator.comparingInt(RowResult::index))
                    .map(RowResult::response)
                    .toList();
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse BSE award announcement payload", e);
        }
    }

    private record RowResult(int index, AwardStockResponse response) {
    }

    private AwardStockResponse toPartialResponse(JsonNode row) {
        String companyName = text(row, "SLONGNAME");
        String symbol = resolveSymbol(companyName, text(row, "NSURL"));
        String announcementHeadline = firstNonBlank(text(row, "MORE"), text(row, "HEADLINE"), text(row, "NEWSSUB"));

        return new AwardStockResponse(
                companyName,
                symbol,
                extractOrderFromWho(announcementHeadline),
                extractOrderAmount(firstNonBlank(text(row, "MORE"), announcementHeadline)),
                "—",
                null,
                null,
                announcementHeadline,
                text(row, "DT_TM"),
                text(row, "NSURL")
        );
    }

    private URI buildUri(int pageNo, String prevDate, String toDate, String search) {
        return UriComponentsBuilder.fromHttpUrl(BSE_ANNOUNCEMENT_URL)
                .queryParam("pageno", pageNo)
                .queryParam("strCat", "Company Update")
                .queryParam("strPrevDate", prevDate)
                .queryParam("strScrip", "")
                .queryParam("strSearch", search)
                .queryParam("strToDate", toDate)
                .queryParam("strType", "C")
                .queryParam("subcategory", "Award of Order / Receipt of Order")
                .build()
                .encode()
                .toUri();
    }

    private AwardStockResponse toResponse(JsonNode row) {
        AwardStockResponse partial = toPartialResponse(row);

        if (partial.symbol() == null || partial.symbol().isBlank()) {
            return partial;
        }

        try {
            FundamentalsResponse fundamentals = screenerScraperService.fetchFundamentals(partial.symbol());
            FundamentalScoreResponse score = fundamentalScoreService.analyze(fundamentals);
            return new AwardStockResponse(
                    partial.companyName(),
                    partial.symbol(),
                    partial.orderFromWho(),
                    partial.orderAmount(),
                    fundamentals.marketCap(),
                    score.finalScore(),
                    score.rating(),
                    partial.announcementHeadline(),
                    partial.announcementDate(),
                    partial.sourceUrl()
            );
        } catch (Exception ignored) {
            return partial;
        }
    }

    private String resolveSymbol(String companyName, String sourceUrl) {
        LinkedHashSet<String> candidates = new LinkedHashSet<>();

        addSearchMatches(candidates, companyName);
        String normalized = normalizeCompanyName(companyName);
        if (!normalized.equalsIgnoreCase(companyName)) {
            addSearchMatches(candidates, normalized);
        }

        String urlSymbol = extractSymbolFromUrl(sourceUrl);
        if (urlSymbol != null && !urlSymbol.isBlank()) {
            candidates.add(urlSymbol.toUpperCase(Locale.ROOT));
        }

        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate.toUpperCase(Locale.ROOT);
            }
        }

        return null;
    }

    private void addSearchMatches(Set<String> candidates, String query) {
        if (query == null || query.isBlank()) {
            return;
        }

        List<StockSearchResult> results = stockSearchService.search(query, 10);
        for (StockSearchResult result : results) {
            if (result.name().equalsIgnoreCase(query)) {
                candidates.add(result.symbol());
            }
        }
        for (StockSearchResult result : results) {
            candidates.add(result.symbol());
        }
    }

    private String extractSymbolFromUrl(String sourceUrl) {
        if (sourceUrl == null || sourceUrl.isBlank()) {
            return null;
        }

        String[] parts = sourceUrl.split("/");
        if (parts.length < 2) {
            return null;
        }

        String candidate = parts[parts.length - 2];
        return candidate == null || candidate.isBlank() ? null : candidate;
    }

    private String extractOrderFromWho(String text) {
        if (text == null || text.isBlank()) {
            return "—";
        }

        Matcher matcher = COMPANY_FROM_PATTERN.matcher(text.replace('\r', ' ').replace('\n', ' '));
        if (!matcher.find()) {
            return "—";
        }

        String result = matcher.group(1).trim();
        result = stripTrailingPhrases(result, " for ", " worth ", " amount ", " vide ", " dated ", " on ", " as ");
        result = result.replaceAll("[\\.,;]+$", "").trim();
        return result.isBlank() ? "—" : result;
    }

    private String extractOrderAmount(String text) {
        if (text == null || text.isBlank()) {
            return "—";
        }

        Matcher moneyMatcher = MONEY_PATTERN.matcher(text);
        if (moneyMatcher.find()) {
            return moneyMatcher.group().replaceAll("\\s+", " ").trim();
        }

        Matcher wordMatcher = MONEY_WORD_PATTERN.matcher(text);
        if (wordMatcher.find()) {
            return wordMatcher.group().replaceAll("\\s+", " ").trim();
        }

        return "—";
    }

    private String stripTrailingPhrases(String value, String... markers) {
        String result = value;
        for (String marker : markers) {
            int index = indexOfIgnoreCase(result, marker);
            if (index > 0) {
                result = result.substring(0, index).trim();
            }
        }
        return result;
    }

    private int indexOfIgnoreCase(String value, String needle) {
        return value.toLowerCase(Locale.ROOT).indexOf(needle.trim().toLowerCase(Locale.ROOT));
    }

    private String normalizeCompanyName(String companyName) {
        if (companyName == null) {
            return "";
        }

        return companyName
                .replaceAll("(?i)\\b(limited|ltd|private|pvt|company)\\b", "")
                .replaceAll("[\\-\\$]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private String text(JsonNode node, String fieldName) {
        JsonNode child = node.get(fieldName);
        return child == null || child.isNull() ? "" : child.asText("").trim();
    }
}