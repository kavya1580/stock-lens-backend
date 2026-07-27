package com.stockdashboard.service;

import com.stockdashboard.dto.AnnouncedResultResponse;
import com.stockdashboard.dto.FundamentalScoreResponse;
import com.stockdashboard.dto.FundamentalsResponse;
import com.stockdashboard.dto.PagedResult;
import com.stockdashboard.dto.UpcomingResultResponse;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Replaces BseResultsCalendarService: instead of regex-classifying BSE's generic
 * "Board Meeting" announcement feed, this reads the user's own Screener.in
 * account's dedicated results feed at /results/latest/ directly (login-gated -
 * see ScreenerAuthService).
 *
 * IMPORTANT SCOPE NOTE: verified against the live, authenticated account, Screener
 * does not expose any forward-looking "upcoming results" feed - /results/calendar/
 * doesn't exist, and both /results/latest/ and /announcements/results/ only ever
 * show results that have *already* been declared. fetchUpcomingResults() therefore
 * always returns an empty page rather than fabricating data; this can be revisited
 * if a real upcoming-results source is found later.
 *
 * /results/latest/ supports a per-day filter via result_update_date__day/month/year
 * query params (confirmed live: different days return different company sets), so
 * announced results are sourced by querying one day at a time across a caller-chosen
 * lookback window - today alone, or today + yesterday (see MIN/MAX_LOOKBACK_DAYS) -
 * and tagging each row with the day it was fetched under; there is no per-row date
 * in the page markup itself. Each company block also
 * carries its own latest-quarter Sales/Net Profit straight from a data-table (marked
 * with data-sales-latest-quarter/data-np-latest-quarter), so those figures are read
 * directly from this page instead of re-deriving them via ScreenerScraperService.
 *
 * NOTE ON FRAGILITY (same caveat as ScreenerScraperService/ScreenerAuthService):
 * this parses Screener's live HTML, not a documented API, and will need re-tuning
 * if Screener changes this markup.
 */
@Service
public class ScreenerResultsCalendarService {

    private static final Logger log = LoggerFactory.getLogger(ScreenerResultsCalendarService.class);

    private static final String RESULTS_LATEST_URL = "https://www.screener.in/results/latest/";
    private static final String COMPANY_URL_TEMPLATE = "https://www.screener.in/company/%s/consolidated/";

    private static final Pattern COMPANY_HREF_PATTERN = Pattern.compile("^/company/([^/]+)/?");
    private static final DateTimeFormatter DISPLAY_DATE_FORMATTER = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    // The only lookback window the API accepts: today alone, or today + yesterday. Keeps row
    // volume low enough that the small ENRICHMENT_EXECUTOR pool can actually get through most/all
    // rows instead of falling behind on a wide date range.
    private static final int MIN_LOOKBACK_DAYS = 1;
    private static final int MAX_LOOKBACK_DAYS = 2;

    // Sub-200 Cr companies are typically illiquid/thinly traded. Screener's own mcap_min/mcap_max
    // query params look like they'd do this server-side, but verified live that adding them makes
    // Screener silently drop the day filter and return an unrelated default listing instead - so
    // this is applied ourselves in fetchRowsForDay using the market cap already parsed per row.
    private static final int MIN_MARKET_CAP_CR = 200;

    // I/O-bound (network scrape via ScreenerScraperService/Screener day-filter fetches), so a
    // higher cap than CPU core count is fine - one request per day, low volume either way.
    private static final ExecutorService DAY_FETCH_EXECUTOR = Executors.newFixedThreadPool(
            Math.max(4, Math.min(12, Runtime.getRuntime().availableProcessors() * 2))
    );
    // A single day's /results/latest/ page is one HTTP request - 12s is generous for that alone.
    private static final long DAY_FETCH_TIMEOUT_SECONDS = 12;

    // Per-company enrichment (ScreenerScraperService.fetchFundamentals) hits Screener's own
    // company pages directly, and empirically Screener throttles/stalls most concurrent requests
    // past a handful (observed: ~3 succeeding regardless of concurrency level, the rest hanging
    // until timeout) - this is independent of whether the request carries our session cookie.
    // Keeping this pool small avoids tripping that and lets requests actually complete instead of
    // mostly timing out.
    private static final ExecutorService ENRICHMENT_EXECUTOR = Executors.newFixedThreadPool(3);
    // completeOnTimeout counts from submission, not from when a queued task starts running, so
    // this has to cover both a single fetchFundamentals call AND queueing behind the small pool
    // above for a full page of rows.
    private static final long ENRICHMENT_TIMEOUT_SECONDS = 120;
    private static final int PAGE_SIZE = 25;

    private final ScreenerAuthService screenerAuthService;
    private final ScreenerScraperService screenerScraperService;
    private final FundamentalScoreService fundamentalScoreService;
    private final ResultsEnrichmentProgressTracker enrichmentProgressTracker;

    public ScreenerResultsCalendarService(
            ScreenerAuthService screenerAuthService,
            ScreenerScraperService screenerScraperService,
            FundamentalScoreService fundamentalScoreService,
            ResultsEnrichmentProgressTracker enrichmentProgressTracker
    ) {
        this.screenerAuthService = screenerAuthService;
        this.screenerScraperService = screenerScraperService;
        this.fundamentalScoreService = fundamentalScoreService;
        this.enrichmentProgressTracker = enrichmentProgressTracker;
    }

    /** Polled by the frontend while an announced-results fetch is in flight. */
    public ResultsEnrichmentProgressTracker.Snapshot getAnnouncedResultsProgress() {
        return enrichmentProgressTracker.snapshot();
    }

    private record ScreenerRow(
            String companyName, String symbol, LocalDate date, String rawDateText, String sourceUrl,
            String marketCap,
            String latestQuarterSales, String priorQuarterSales,
            String latestQuarterNetProfit, String priorQuarterNetProfit, String priorYearQuarterNetProfit
    ) {
    }

    /**
     * Always empty - see the class-level note. Kept as a real endpoint (rather than removed)
     * so the frontend's Upcoming tab degrades to "no results" instead of erroring.
     */
    @Cacheable(value = "resultsUpcoming", key = "#pageNo")
    public PagedResult<UpcomingResultResponse> fetchUpcomingResults(int pageNo) {
        return new PagedResult<>(List.of(), Math.max(1, pageNo), 0, 0);
    }

    // Deliberately NOT @Cacheable: the previous PagedResult-level cache stored whole pages
    // including rows that fell back to "Insufficient Data" (e.g. from a Screener 429), and once
    // cached that bad page kept being served for the full 30-min TTL even after Screener's rate
    // limit had cleared. Per-symbol enrichment already goes through fetchFundamentals's own
    // "fundamentals" cache (24h TTL, keyed by symbol) - successes are just as fast, and a failed
    // lookup isn't cached there (Spring doesn't cache thrown exceptions), so the next request here
    // naturally retries only the symbols that actually failed.
    public PagedResult<AnnouncedResultResponse> fetchAnnouncedResults(int pageNo, int lookbackDays) {
        List<ScreenerRow> rows = fetchAnnouncedRows(lookbackDays);
        return paginate(rows, pageNo, this::toAnnouncedResponse, this::toAnnouncedPartial);
    }

    private List<ScreenerRow> fetchAnnouncedRows(int lookbackDays) {
        int clampedLookback = Math.max(MIN_LOOKBACK_DAYS, Math.min(MAX_LOOKBACK_DAYS, lookbackDays));
        LocalDate end = LocalDate.now();
        LocalDate start = end.minusDays(clampedLookback - 1);

        List<LocalDate> days = new ArrayList<>();
        for (LocalDate day = start; !day.isAfter(end); day = day.plusDays(1)) {
            days.add(day);
        }

        List<CompletableFuture<List<ScreenerRow>>> futures = days.stream()
                .map(day -> CompletableFuture
                        .supplyAsync(() -> fetchRowsForDay(day), DAY_FETCH_EXECUTOR)
                        .completeOnTimeout(null, DAY_FETCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                        .handle((result, ex) -> {
                            if (result != null) {
                                return result;
                            }
                            if (ex != null) {
                                log.warn("Day-fetch FAILED for {}: {} - that day contributes 0 rows this request",
                                        day, String.valueOf(ex.getCause() != null ? ex.getCause() : ex));
                            } else {
                                log.warn("Day-fetch TIMED OUT for {} after {}s - that day contributes 0 rows this request",
                                        day, DAY_FETCH_TIMEOUT_SECONDS);
                            }
                            return List.<ScreenerRow>of();
                        }))
                .toList();

        LinkedHashMap<String, ScreenerRow> bySymbol = new LinkedHashMap<>();
        for (CompletableFuture<List<ScreenerRow>> future : futures) {
            for (ScreenerRow row : future.join()) {
                bySymbol.putIfAbsent(row.symbol(), row);
            }
        }

        List<ScreenerRow> rows = new ArrayList<>(bySymbol.values());
        rows.sort(Comparator.comparing(ScreenerRow::date).reversed());
        return rows;
    }

    private List<ScreenerRow> fetchRowsForDay(LocalDate day) {
        // Verified live (see conversation) that adding mcap=custom/mcap_min/mcap_max to this URL
        // makes Screener silently DROP the day filter and return a fixed default listing instead -
        // same 25 companies regardless of which day was requested, and still including sub-200cr
        // names, i.e. it filters neither by day nor by market cap once those params are present.
        // Day-scoping is essential here, so market cap is instead filtered from the already-parsed
        // per-row value below, entirely in our own code.
        String url = RESULTS_LATEST_URL + "?result_update_date__day=" + day.getDayOfMonth()
                + "&result_update_date__month=" + day.getMonthValue()
                + "&result_update_date__year=" + day.getYear();
        Document doc = screenerAuthService.getAuthenticated(url);

        // font-weight-500 is only ever used (site-wide) on the actual company link in each block -
        // the adjacent "PDF" source link uses a different class, so this alone excludes it.
        Elements companyLinks = doc.select("a.font-weight-500[href^=/company/]");

        List<ScreenerRow> rows = new ArrayList<>();
        for (Element link : companyLinks) {
            String symbol = extractSymbol(link.attr("href"));
            if (symbol == null) {
                continue;
            }

            Element nameSpan = link.selectFirst("span.hover-link");
            String companyName = nameSpan != null && !nameSpan.text().isBlank()
                    ? nameSpan.text().trim() : symbol;

            Element headerDiv = link.closest("div.flex-row");
            Element tableContainer = headerDiv != null ? headerDiv.nextElementSibling() : null;
            Element table = tableContainer != null ? tableContainer.selectFirst("table.data-table") : null;

            Element mcapStrong = headerDiv != null ? headerDiv.selectFirst("span[data-mcap] .strong") : null;
            String mcapRaw = mcapStrong != null ? mcapStrong.text().trim() : "";
            double marketCapCr = parseIndianNumber(mcapRaw);
            if (marketCapCr > 0 && marketCapCr < MIN_MARKET_CAP_CR) {
                continue;
            }
            String marketCap = !mcapRaw.isBlank() ? "₹ " + mcapRaw + " Cr." : "—";

            rows.add(new ScreenerRow(
                    companyName, symbol, day, DISPLAY_DATE_FORMATTER.format(day),
                    String.format(COMPANY_URL_TEMPLATE, symbol.toLowerCase(Locale.ROOT)),
                    marketCap,
                    tdText(table, "tr[data-sales]", 2), tdText(table, "tr[data-sales]", 3),
                    tdText(table, "tr[data-net-profit]", 2), tdText(table, "tr[data-net-profit]", 3),
                    tdText(table, "tr[data-net-profit]", 4)
            ));
        }
        return rows;
    }

    /** Cell layout in each data-table row is [label, YOY, latest quarter, prior quarter, year-ago quarter]. */
    private String tdText(Element table, String rowSelector, int index) {
        if (table == null) {
            return null;
        }
        Element row = table.selectFirst(rowSelector);
        if (row == null) {
            return null;
        }
        Elements cells = row.select("td");
        if (index >= cells.size()) {
            return null;
        }
        String text = cells.get(index).text().trim();
        return text.isBlank() ? null : text;
    }

    /** Strips currency/grouping characters (e.g. "12,62,293" -> 1262293.0); 0 if unparseable. */
    private double parseIndianNumber(String raw) {
        if (raw == null || raw.isBlank()) {
            return 0;
        }
        String cleaned = raw.replaceAll("[^0-9.]", "");
        if (cleaned.isEmpty()) {
            return 0;
        }
        try {
            return Double.parseDouble(cleaned);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private String extractSymbol(String href) {
        if (href == null) {
            return null;
        }
        Matcher matcher = COMPANY_HREF_PATTERN.matcher(href);
        return matcher.find() ? matcher.group(1).toUpperCase(Locale.ROOT) : null;
    }

    private <T> PagedResult<T> paginate(
            List<ScreenerRow> rows, int pageNo, Function<ScreenerRow, T> fullMapper, Function<ScreenerRow, T> partialMapper
    ) {
        int safePageNo = Math.max(1, pageNo);
        int fromIndex = Math.min((safePageNo - 1) * PAGE_SIZE, rows.size());
        int toIndex = Math.min(fromIndex + PAGE_SIZE, rows.size());
        List<ScreenerRow> pageRows = rows.subList(fromIndex, toIndex);

        int progressGeneration = enrichmentProgressTracker.start(pageRows.size());
        List<CompletableFuture<T>> futures = pageRows.stream()
                .map(row -> withTimeout(() -> fullMapper.apply(row), partialMapper.apply(row), row.symbol())
                        .whenComplete((result, ex) -> enrichmentProgressTracker.increment(progressGeneration)))
                .toList();

        List<T> items = futures.stream().map(CompletableFuture::join).toList();
        int totalPages = rows.isEmpty() ? 0 : (int) Math.ceil(rows.size() / (double) PAGE_SIZE);
        return new PagedResult<>(items, safePageNo, rows.size(), totalPages);
    }

    /**
     * completeOnTimeout()+exceptionally() previously returned the same fallback for a real
     * timeout AND for any thrown exception, so there was no way to tell which one was actually
     * happening from logs. Using orTimeout()+handle() instead lets us log which case fired and
     * with what cause, since that's the only way to find the real bottleneck (vs. guessing).
     */
    private <T> CompletableFuture<T> withTimeout(Supplier<T> supplier, T fallback, String symbol) {
        long startedAt = System.currentTimeMillis();
        return CompletableFuture
                .supplyAsync(supplier, ENRICHMENT_EXECUTOR)
                .orTimeout(ENRICHMENT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .handle((result, ex) -> {
                    if (ex == null) {
                        return result;
                    }
                    long elapsedMs = System.currentTimeMillis() - startedAt;
                    if (ex instanceof TimeoutException || ex.getCause() instanceof TimeoutException) {
                        log.warn("Enrichment TIMED OUT for {} after {}ms (budget {}s)", symbol, elapsedMs, ENRICHMENT_TIMEOUT_SECONDS);
                    } else {
                        log.warn("Enrichment FAILED for {} after {}ms: {}", symbol, elapsedMs, String.valueOf(ex.getCause() != null ? ex.getCause() : ex));
                    }
                    return fallback;
                });
    }

    private AnnouncedResultResponse toAnnouncedPartial(ScreenerRow row) {
        return new AnnouncedResultResponse(
                row.companyName(), row.symbol(), valueOrDash(row.marketCap()), null, null,
                row.rawDateText(),
                valueOrDash(row.latestQuarterSales()), valueOrDash(row.latestQuarterNetProfit()),
                computeQoqGrowth(row), computeYoyGrowth(row),
                "Insufficient Data", "Insufficient Data", "Fundamentals lookup timed out.",
                "Result declared", row.rawDateText(), row.sourceUrl()
        );
    }

    private AnnouncedResultResponse toAnnouncedResponse(ScreenerRow row) {
        AnnouncedResultResponse partial = toAnnouncedPartial(row);
        try {
            FundamentalsResponse fundamentals = screenerScraperService.fetchFundamentals(row.symbol());
            FundamentalScoreResponse score = fundamentalScoreService.analyze(fundamentals);
            ResultExpectationCalculator.OutcomeResult outcome =
                    ResultExpectationCalculator.computeOutcome(fundamentals.netProfitQuarterly());

            String marketCap = fundamentals.marketCap() == null || fundamentals.marketCap().isBlank()
                    || "—".equals(fundamentals.marketCap())
                    ? partial.marketCap() : fundamentals.marketCap();
            return new AnnouncedResultResponse(
                    partial.companyName(), partial.symbol(), marketCap,
                    score.finalScore(), score.rating(),
                    partial.resultDate(), partial.latestQuarterSales(), partial.latestQuarterNetProfit(),
                    partial.qoqProfitGrowthPercent(), partial.yoyProfitGrowthPercent(),
                    outcome.priorTrendDirection(), outcome.actualVsExpected(), outcome.note(),
                    partial.announcementHeadline(), partial.announcementDate(), partial.sourceUrl()
            );
        } catch (Exception e) {
            log.warn("Enrichment threw for {}: {}", row.symbol(), String.valueOf(e.getCause() != null ? e.getCause() : e));
            return partial;
        }
    }

    private String valueOrDash(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }

    private Double computeQoqGrowth(ScreenerRow row) {
        double prior = ScoreUtils.parseDouble(row.priorQuarterNetProfit());
        double latest = ScoreUtils.parseDouble(row.latestQuarterNetProfit());
        if (!ScoreUtils.isValid(prior) || !ScoreUtils.isValid(latest) || prior == 0) {
            return null;
        }
        return ((latest - prior) / Math.abs(prior)) * 100.0;
    }

    private Double computeYoyGrowth(ScreenerRow row) {
        double yearAgo = ScoreUtils.parseDouble(row.priorYearQuarterNetProfit());
        double latest = ScoreUtils.parseDouble(row.latestQuarterNetProfit());
        if (!ScoreUtils.isValid(yearAgo) || !ScoreUtils.isValid(latest) || yearAgo == 0) {
            return null;
        }
        return ((latest - yearAgo) / Math.abs(yearAgo)) * 100.0;
    }
}
