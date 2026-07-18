package com.stockdashboard.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockdashboard.dto.AnnouncedResultResponse;
import com.stockdashboard.dto.FundamentalScoreResponse;
import com.stockdashboard.dto.FundamentalsResponse;
import com.stockdashboard.dto.PagedResult;
import com.stockdashboard.dto.StockSearchResult;
import com.stockdashboard.dto.UpcomingResultResponse;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
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

/**
 * Scrapes BSE's public "Board Meeting" announcements feed to build a results
 * calendar: intimations of an upcoming board meeting called to approve
 * financial results ("upcoming"), and outcome filings where results were
 * already declared ("announced"). Same source API and enrichment pipeline as
 * BseAwardStockService, just a different category and row classification.
 *
 * NOTE ON FRAGILITY: BSE's category taxonomy and headline phrasing aren't a
 * documented contract, so the classification regexes below (RESULTS_KEYWORD,
 * OUTCOME, MEETING_DATE) may need a round of tuning against live payloads —
 * same caveat as ScreenerScraperService's HTML scraping.
 */
@Service
public class BseResultsCalendarService {

    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36";
    private static final String BSE_ANNOUNCEMENT_URL =
            "https://api.bseindia.com/BseIndiaAPI/api/AnnSubCategoryGetData/w";
    private static final String BSE_ATTACHMENT_BASE_URL =
            "https://www.bseindia.com/xml-data/corpfiling/AttachLive/";
    private static final String BSE_SITE_BASE_URL = "https://www.bseindia.com/";

    private static final Pattern RESULTS_KEYWORD_PATTERN = Pattern.compile("(?i)\\bresults?\\b");
    private static final Pattern OUTCOME_PATTERN = Pattern.compile(
            "(?i)\\b(outcome of the board meeting|considered and approved|un[- ]?audited financial results|audited financial results)\\b");
    private static final Pattern MEETING_DATE_PATTERN = Pattern.compile(
            "(?i)(?:will be held on|scheduled on|to be held on)\\s*([0-3]?\\d[-/][01]?\\d[-/]\\d{2,4})");

    // I/O-bound (network scrape), so a higher cap than CPU core count is fine here.
    private static final ExecutorService LOOKUP_EXECUTOR = Executors.newFixedThreadPool(
            Math.max(4, Math.min(12, Runtime.getRuntime().availableProcessors() * 2))
    );
    // ScreenerScraperService itself uses a 10s Jsoup timeout per request (and may retry once on a
    // 404 with a second 10s request), so this must comfortably exceed that or well-covered stocks
    // spuriously show "Insufficient Data" whenever Screener.in is briefly slow or the thread pool is busy.
    private static final long ROW_LOOKUP_TIMEOUT_SECONDS = 12;

    // BSE's own fixed page size for this feed (not documented anywhere, confirmed by probing the live API).
    private static final int BSE_PAGE_SIZE = 50;

    private final ObjectMapper objectMapper;
    private final StockSearchService stockSearchService;
    private final ScreenerScraperService screenerScraperService;
    private final FundamentalScoreService fundamentalScoreService;
    private final WebClient webClient;

    public BseResultsCalendarService(
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

    @Cacheable(value = "resultsUpcoming", key = "#pageNo + '-' + #prevDate + '-' + #toDate + '-' + #search")
    public PagedResult<UpcomingResultResponse> fetchUpcomingResults(int pageNo, String prevDate, String toDate, String search) {
        RowsPage rowsPage = fetchRows(pageNo, prevDate, toDate, search);

        List<CompletableFuture<UpcomingResultResponse>> futures = new ArrayList<>();
        for (JsonNode row : rowsPage.rows()) {
            String headline = headlineOf(row);
            Matcher dateMatcher = MEETING_DATE_PATTERN.matcher(headline);
            // Don't veto on OUTCOME_PATTERN here: nearly every upcoming-results intimation says
            // "to consider and approve the Un-Audited Financial Results", which itself matches
            // OUTCOME_PATTERN's generic "results" phrasing even though nothing has happened yet.
            // An explicit future-scheduling date (dateMatcher) is the reliable signal instead.
            boolean isUpcoming = RESULTS_KEYWORD_PATTERN.matcher(headline).find() && dateMatcher.find();
            if (!isUpcoming) {
                continue;
            }
            String boardMeetingDate = dateMatcher.group(1);
            futures.add(withTimeout(() -> toUpcomingResponse(row, boardMeetingDate), toUpcomingPartial(row, boardMeetingDate)));
        }

        List<UpcomingResultResponse> items = futures.stream().map(CompletableFuture::join).toList();
        return new PagedResult<>(items, pageNo, rowsPage.totalCount(), totalPages(rowsPage.totalCount()));
    }

    @Cacheable(value = "resultsAnnounced", key = "#pageNo + '-' + #prevDate + '-' + #toDate + '-' + #search")
    public PagedResult<AnnouncedResultResponse> fetchAnnouncedResults(int pageNo, String prevDate, String toDate, String search) {
        RowsPage rowsPage = fetchRows(pageNo, prevDate, toDate, search);

        List<CompletableFuture<AnnouncedResultResponse>> futures = new ArrayList<>();
        for (JsonNode row : rowsPage.rows()) {
            String headline = headlineOf(row);
            // OUTCOME_PATTERN's "considered and approved"/"un-audited financial results" phrasing is
            // generic — it also matches upcoming intimations describing what a FUTURE meeting will
            // consider. Require "results" (excludes bonus/dividend/restructuring/guarantee filings) AND
            // exclude anything that also carries explicit future-scheduling language (a genuine outcome
            // filing never says "is scheduled on/will be held on/to be held on <future date>").
            boolean isAnnounced = OUTCOME_PATTERN.matcher(headline).find()
                    && RESULTS_KEYWORD_PATTERN.matcher(headline).find()
                    && !MEETING_DATE_PATTERN.matcher(headline).find();
            if (!isAnnounced) {
                continue;
            }
            futures.add(withTimeout(() -> toAnnouncedResponse(row), toAnnouncedPartial(row)));
        }

        List<AnnouncedResultResponse> items = futures.stream().map(CompletableFuture::join).toList();
        return new PagedResult<>(items, pageNo, rowsPage.totalCount(), totalPages(rowsPage.totalCount()));
    }

    private int totalPages(int totalCount) {
        return totalCount <= 0 ? 0 : (int) Math.ceil(totalCount / (double) BSE_PAGE_SIZE);
    }

    private <T> CompletableFuture<T> withTimeout(java.util.function.Supplier<T> supplier, T fallback) {
        return CompletableFuture
                .supplyAsync(supplier, LOOKUP_EXECUTOR)
                .completeOnTimeout(fallback, ROW_LOOKUP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .exceptionally(ex -> fallback);
    }

    private record RowsPage(List<JsonNode> rows, int totalCount) {
    }

    private RowsPage fetchRows(int pageNo, String prevDate, String toDate, String search) {
        String payload = webClient.get()
                .uri(buildUri(pageNo, prevDate, toDate, search))
                .retrieve()
                .bodyToMono(String.class)
                .block(Duration.ofSeconds(30));

        if (payload == null || payload.isBlank()) {
            return new RowsPage(List.of(), 0);
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

            return new RowsPage(rows, totalCount);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse BSE board meeting announcement payload", e);
        }
    }

    private URI buildUri(int pageNo, String prevDate, String toDate, String search) {
        return UriComponentsBuilder.fromHttpUrl(BSE_ANNOUNCEMENT_URL)
                .queryParam("pageno", pageNo)
                .queryParam("strCat", "Board Meeting")
                .queryParam("strPrevDate", prevDate)
                .queryParam("strScrip", "")
                .queryParam("strSearch", search)
                .queryParam("strToDate", toDate)
                .queryParam("strType", "C")
                .queryParam("subcategory", "-1")
                .build()
                .encode()
                .toUri();
    }

    private String headlineOf(JsonNode row) {
        return firstNonBlank(text(row, "MORE"), text(row, "HEADLINE"), text(row, "NEWSSUB"));
    }

    private UpcomingResultResponse toUpcomingPartial(JsonNode row, String boardMeetingDate) {
        String companyName = text(row, "SLONGNAME");
        return new UpcomingResultResponse(
                companyName,
                resolveSymbol(companyName, text(row, "NSURL")),
                "—", null, null,
                boardMeetingDate,
                "Insufficient Data", "Fundamentals lookup timed out.",
                headlineOf(row),
                text(row, "DT_TM"),
                resolveSourceUrl(row)
        );
    }

    private UpcomingResultResponse toUpcomingResponse(JsonNode row, String boardMeetingDate) {
        UpcomingResultResponse partial = toUpcomingPartial(row, boardMeetingDate);
        if (partial.symbol() == null || partial.symbol().isBlank()) {
            return partial;
        }

        try {
            FundamentalsResponse fundamentals = screenerScraperService.fetchFundamentals(partial.symbol());
            FundamentalScoreResponse score = fundamentalScoreService.analyze(fundamentals);
            ResultExpectationCalculator.ExpectationResult expectation =
                    ResultExpectationCalculator.computeExpectation(fundamentals.netProfitQuarterly());

            return new UpcomingResultResponse(
                    partial.companyName(),
                    partial.symbol(),
                    fundamentals.marketCap(),
                    score.finalScore(),
                    score.rating(),
                    partial.boardMeetingDate(),
                    expectation.direction(),
                    expectation.note(),
                    partial.announcementHeadline(),
                    partial.announcementDate(),
                    partial.sourceUrl()
            );
        } catch (Exception ignored) {
            return partial;
        }
    }

    private AnnouncedResultResponse toAnnouncedPartial(JsonNode row) {
        String companyName = text(row, "SLONGNAME");
        return new AnnouncedResultResponse(
                companyName,
                resolveSymbol(companyName, text(row, "NSURL")),
                "—", null, null,
                text(row, "DT_TM"),
                "—", "—", null,
                "Insufficient Data", "Insufficient Data", "Fundamentals lookup timed out.",
                headlineOf(row),
                text(row, "DT_TM"),
                resolveSourceUrl(row)
        );
    }

    private AnnouncedResultResponse toAnnouncedResponse(JsonNode row) {
        AnnouncedResultResponse partial = toAnnouncedPartial(row);
        if (partial.symbol() == null || partial.symbol().isBlank()) {
            return partial;
        }

        try {
            FundamentalsResponse fundamentals = screenerScraperService.fetchFundamentals(partial.symbol());
            FundamentalScoreResponse score = fundamentalScoreService.analyze(fundamentals);
            ResultExpectationCalculator.OutcomeResult outcome =
                    ResultExpectationCalculator.computeOutcome(fundamentals.netProfitQuarterly());

            String latestSales = latestSeriesValue(fundamentals.salesQuarterly());
            String latestNetProfit = latestSeriesValue(fundamentals.netProfitQuarterly());
            Double qoqGrowth = latestQoqGrowth(fundamentals.netProfitQuarterly());

            return new AnnouncedResultResponse(
                    partial.companyName(),
                    partial.symbol(),
                    fundamentals.marketCap(),
                    score.finalScore(),
                    score.rating(),
                    partial.resultDate(),
                    latestSales,
                    latestNetProfit,
                    qoqGrowth,
                    outcome.priorTrendDirection(),
                    outcome.actualVsExpected(),
                    outcome.note(),
                    partial.announcementHeadline(),
                    partial.announcementDate(),
                    partial.sourceUrl()
            );
        } catch (Exception ignored) {
            return partial;
        }
    }

    private String latestSeriesValue(java.util.Map<String, String> series) {
        if (series == null || series.isEmpty()) {
            return "—";
        }
        return series.values().stream().reduce((first, second) -> second).orElse("—");
    }

    private Double latestQoqGrowth(java.util.Map<String, String> series) {
        List<Double> values = ScoreUtils.parseSeriesValues(series);
        if (values.size() < 2) {
            return null;
        }
        double prior = values.get(values.size() - 2);
        double latest = values.get(values.size() - 1);
        if (!ScoreUtils.isValid(prior) || !ScoreUtils.isValid(latest) || prior == 0) {
            return null;
        }
        return ((latest - prior) / Math.abs(prior)) * 100.0;
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
