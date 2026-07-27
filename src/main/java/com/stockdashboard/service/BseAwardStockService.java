package com.stockdashboard.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockdashboard.dto.AwardStockResponse;
import com.stockdashboard.dto.FundamentalScoreResponse;
import com.stockdashboard.dto.FundamentalsResponse;
import com.stockdashboard.dto.PagedResult;
import com.stockdashboard.dto.StockSearchResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(BseAwardStockService.class);

    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36";
    private static final String BSE_ANNOUNCEMENT_URL =
            "https://api.bseindia.com/BseIndiaAPI/api/AnnSubCategoryGetData/w";
    private static final String BSE_ATTACHMENT_BASE_URL =
            "https://www.bseindia.com/xml-data/corpfiling/AttachLive/";
    private static final String BSE_SITE_BASE_URL = "https://www.bseindia.com/";

    private static final Pattern COMPANY_FROM_PATTERN = Pattern.compile(
            "(?i)\\b(?:from|received from|received order from|order from|award from|awarded by|letter of award from|letter of intent from|loa from|loi from|purchase order from|agreement with|contract with|mou with|tie[- ]?up with|understanding with)\\s+((?:(?!\\.\\s|[,;\\n]).)+)");
    private static final Pattern MONEY_PATTERN = Pattern.compile(
            "(?i)(?:Rs\\.?|₹|INR|USD)\\s*[0-9][0-9,]*(?:\\.[0-9]+)?(?:\\s*(?:Crores?|Crore|Cr|Lakhs?|Lacs?|L|Million|Billion|Thousand|Mn))?(?:\\s*\\([^)]*\\))?");
    private static final Pattern MONEY_WORD_PATTERN = Pattern.compile(
            "(?i)\\b[0-9][0-9,]*(?:\\.[0-9]+)?\\s*(?:Crores?|Crore|Cr|Lakhs?|Lacs?|L|Million|Billion|Thousand|Mn)\\b");
    private static final Pattern MONEY_BARE_PATTERN = Pattern.compile(
            "(?i)(?:Rs\\.?|₹|INR)\\s*[:\\-]?\\s*(\\d{1,2}(?:,\\d{2})*,\\d{3}(?:\\.\\d+)?)\\s*/?-?");
    private static final Pattern NON_BUSINESS_ORDER_PATTERN = Pattern.compile(
            "(?i)\\b(income tax act|section 271|itat|excise\\s*&?\\s*taxation|assessing authority|gst act|penalty order|demand order|tribunal|tax authorit(?:y|ies))\\b");
    // The actual defense against Screener's rate limiting is ScreenerRateLimiter, shared by every
    // caller inside ScreenerAuthService - it caps request RATE app-wide, which is what Screener
    // enforces (concurrent connection count alone wasn't the trigger; 429s showed up on the very
    // first request even at low concurrency). This pool just bounds how many rows are in flight
    // waiting on that shared limiter at once - a small number is enough since they all serialize
    // through it anyway.
    private static final ExecutorService LOOKUP_EXECUTOR = Executors.newFixedThreadPool(3);
    // A full page (BSE_PAGE_SIZE rows, up to 3 Screener calls each) all queue through the same
    // rate limiter, so the last row in a page can wait ~90s+ just for its turn before it even
    // starts its own request. This must comfortably exceed that queueing time, not just
    // ScreenerScraperService's per-request retry/backoff time - a short timeout here doesn't stop
    // the underlying task, it just abandons the row early while it keeps running (and retrying)
    // on LOOKUP_EXECUTOR in the background, well after the response has already been sent.
    private static final long ROW_LOOKUP_TIMEOUT_SECONDS = 180;

    // BSE's own fixed page size for this feed (not documented anywhere, confirmed by probing the live API).
    private static final int BSE_PAGE_SIZE = 50;

    private final ObjectMapper objectMapper;
    private final StockSearchService stockSearchService;
    private final ScreenerScraperService screenerScraperService;
    private final FundamentalScoreService fundamentalScoreService;
    private final AwardEnrichmentProgressTracker enrichmentProgressTracker;
    private final WebClient webClient;

    public BseAwardStockService(
            ObjectMapper objectMapper,
            StockSearchService stockSearchService,
            ScreenerScraperService screenerScraperService,
            FundamentalScoreService fundamentalScoreService,
            AwardEnrichmentProgressTracker enrichmentProgressTracker
    ) {
        this.objectMapper = objectMapper;
        this.stockSearchService = stockSearchService;
        this.screenerScraperService = screenerScraperService;
        this.fundamentalScoreService = fundamentalScoreService;
        this.enrichmentProgressTracker = enrichmentProgressTracker;
        this.webClient = WebClient.builder()
                .defaultHeader(HttpHeaders.USER_AGENT, USER_AGENT)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.REFERER, "https://www.bseindia.com/")
                .defaultHeader("X-Requested-With", "XMLHttpRequest")
                .build();
    }

    @Cacheable(value = "awardStocks", key = "#pageNo + '-' + #prevDate + '-' + #toDate + '-' + #search")
    public PagedResult<AwardStockResponse> fetchAwardStocks(int pageNo, String prevDate, String toDate, String search) {
        String payload = webClient.get()
                .uri(buildUri(pageNo, prevDate, toDate, search))
                .retrieve()
                .bodyToMono(String.class)
                .block(Duration.ofSeconds(30));

        if (payload == null || payload.isBlank()) {
            return new PagedResult<>(List.of(), pageNo, 0, 0);
        }

        try {
            JsonNode root = objectMapper.readTree(payload);
            JsonNode table = root.path("Table");
            List<JsonNode> rows = new ArrayList<>();
            if (table.isArray()) {
                table.forEach(rows::add);
            }

            int totalCount = 0;
            JsonNode table1 = root.path("Table1");
            if (table1.isArray() && !table1.isEmpty()) {
                totalCount = table1.get(0).path("ROWCNT").asInt(0);
            }

            int progressGeneration = enrichmentProgressTracker.start(rows.size());
            List<CompletableFuture<RowResult>> futures = new ArrayList<>();
            for (int i = 0; i < rows.size(); i++) {
                final int index = i;
                final JsonNode row = rows.get(i);
                AwardStockResponse fallback = toPartialResponse(row);
                futures.add(CompletableFuture
                        .supplyAsync(() -> new RowResult(index, toResponse(row)), LOOKUP_EXECUTOR)
                        .completeOnTimeout(new RowResult(index, fallback), ROW_LOOKUP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                        .exceptionally(ex -> {
                            log.warn("Row processing failed unexpectedly for company \"{}\": {}",
                                    fallback.companyName(), ex.getMessage());
                            return new RowResult(index, fallback);
                        })
                        .whenComplete((result, ex) -> enrichmentProgressTracker.increment(progressGeneration)));
            }

            List<AwardStockResponse> items = futures.stream()
                    .map(CompletableFuture::join)
                    .sorted(Comparator.comparingInt(RowResult::index))
                    .map(RowResult::response)
                    .toList();

            int totalPages = totalCount <= 0 ? 0 : (int) Math.ceil(totalCount / (double) BSE_PAGE_SIZE);
            return new PagedResult<>(items, pageNo, totalCount, totalPages);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse BSE award announcement payload", e);
        }
    }

    /** Polled by the frontend while an awards fetch is in flight. */
    public AwardEnrichmentProgressTracker.Snapshot getAwardsProgress() {
        return enrichmentProgressTracker.snapshot();
    }

    private record RowResult(int index, AwardStockResponse response) {
    }

    private AwardStockResponse toPartialResponse(JsonNode row) {
        String companyName = text(row, "SLONGNAME");
        String symbol = resolveSymbol(row);
        String announcementHeadline = firstNonBlank(text(row, "MORE"), text(row, "HEADLINE"), text(row, "NEWSSUB"));
        String amountSource = firstNonBlank(text(row, "MORE"), announcementHeadline);

        boolean nonBusinessOrder = NON_BUSINESS_ORDER_PATTERN.matcher(announcementHeadline).find();

        return new AwardStockResponse(
                companyName,
                symbol,
                nonBusinessOrder ? "—" : extractOrderFromWho(announcementHeadline),
                nonBusinessOrder ? "—" : extractOrderAmount(amountSource),
                "—",
                null,
                null,
                announcementHeadline,
                text(row, "DT_TM"),
                resolveSourceUrl(row)
        );
    }

    private String resolveSourceUrl(JsonNode row) {
        String attachment = text(row, "ATTACHMENTNAME");
        if (!attachment.isBlank()) {
            return BSE_ATTACHMENT_BASE_URL + attachment;
        }

        String nsurl = text(row, "NSURL");
        if (nsurl.isBlank()) {
            return "";
        }

        return nsurl.startsWith("http") ? nsurl : BSE_SITE_BASE_URL + nsurl;
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
        } catch (Exception ex) {
            log.warn("Fundamental enrichment failed for company \"{}\" (symbol={}): {}",
                    partial.companyName(), partial.symbol(), ex.getMessage());
            return partial;
        }
    }

    private String resolveSymbol(JsonNode row) {
        String companyName = text(row, "SLONGNAME");
        String sourceUrl = text(row, "NSURL");
        LinkedHashSet<String> candidates = new LinkedHashSet<>();

        // BSE's own scrip code is an exact identifier straight from the announcement itself -
        // try it first, ahead of fuzzy name matching. This is what makes BSE-only/SME-listed
        // companies (never present in our NSE-only symbol list) resolvable at all. Screener.in
        // accepts a bare BSE scrip code as a company URL slug, same as an NSE ticker.
        String scripCode = text(row, "SCRIP_CD");
        if (isBseScripCode(scripCode)) {
            candidates.add(scripCode);
        }

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

        log.info("No symbol resolved for company \"{}\" (BSE scrip={}, nsurl={}) - enrichment skipped",
                companyName, scripCode.isBlank() ? "—" : scripCode, sourceUrl.isBlank() ? "—" : sourceUrl);
        return null;
    }

    private boolean isBseScripCode(String value) {
        return value != null && value.matches("\\d{5,6}");
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

        Matcher bareMatcher = MONEY_BARE_PATTERN.matcher(text);
        if (bareMatcher.find()) {
            return bareMatcher.group().replaceAll("\\s+", " ").trim();
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