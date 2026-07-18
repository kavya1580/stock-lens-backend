package com.stockdashboard.controller;

import com.stockdashboard.dto.AiAnalysisResponse;
import com.stockdashboard.dto.AiChatRequest;
import com.stockdashboard.dto.AiChatResponse;
import com.stockdashboard.dto.FundamentalAnalysisResponse;
import com.stockdashboard.dto.FundamentalScoreResponse;
import com.stockdashboard.dto.AwardStockResponse;
import com.stockdashboard.dto.AnnouncedResultResponse;
import com.stockdashboard.dto.FundamentalsResponse;
import com.stockdashboard.dto.NewsItem;
import com.stockdashboard.dto.PagedResult;
import com.stockdashboard.dto.StockIndicatorResponse;
import com.stockdashboard.dto.StockOverviewResponse;
import com.stockdashboard.dto.StockSearchResult;
import com.stockdashboard.dto.UpcomingResultResponse;
import com.stockdashboard.service.BseAwardStockService;
import com.stockdashboard.service.BseResultsCalendarService;
import com.stockdashboard.service.FundamentalScoreService;
import com.stockdashboard.service.GeminiService;
import com.stockdashboard.service.NewsService;
import com.stockdashboard.service.ScreenerScraperService;
import com.stockdashboard.service.StockAnalysisService;
import com.stockdashboard.service.StockSearchService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/stocks")
public class StockController {

    private final StockSearchService stockSearchService;
    private final StockAnalysisService stockAnalysisService;
    private final FundamentalScoreService fundamentalScoreService;
    private final ScreenerScraperService screenerScraperService;
    private final BseAwardStockService bseAwardStockService;
    private final BseResultsCalendarService bseResultsCalendarService;
    private final GeminiService geminiService;
    private final NewsService newsService;

    public StockController(
            StockSearchService stockSearchService,
            StockAnalysisService stockAnalysisService,
            FundamentalScoreService fundamentalScoreService,
            ScreenerScraperService screenerScraperService,
            BseAwardStockService bseAwardStockService,
            BseResultsCalendarService bseResultsCalendarService,
            GeminiService geminiService,
            NewsService newsService
    ) {
        this.stockSearchService = stockSearchService;
        this.stockAnalysisService = stockAnalysisService;
        this.fundamentalScoreService = fundamentalScoreService;
        this.screenerScraperService = screenerScraperService;
        this.bseAwardStockService = bseAwardStockService;
        this.bseResultsCalendarService = bseResultsCalendarService;
        this.geminiService = geminiService;
        this.newsService = newsService;
    }

    @GetMapping("/search")
    public List<StockSearchResult> search(@RequestParam("q") String query) {
        return stockSearchService.search(query, 10);
    }

    /** Full OHLCV + indicator history — feeds the technical chart panel. */
    @GetMapping("/{symbol}/indicators")
    public StockIndicatorResponse getIndicators(
            @PathVariable String symbol,
            @RequestParam(defaultValue = "NSE") String exchange,
            @RequestParam(defaultValue = "6mo") String range
    ) {
        return stockAnalysisService.getIndicators(symbol, exchange, range);
    }

    /** Fundamental metrics only, scraped from Screener.in. */
    @GetMapping("/{symbol}/fundamentals")
    public FundamentalsResponse getFundamentals(@PathVariable String symbol) {
        return screenerScraperService.fetchFundamentals(symbol.trim().toUpperCase());
    }

    @GetMapping("/{symbol}/fundamentals/analysis")
    public FundamentalAnalysisResponse getFundamentalAnalysis(@PathVariable String symbol) {
        String normalizedSymbol = symbol.trim().toUpperCase();
        FundamentalsResponse fundamentals = screenerScraperService.fetchFundamentals(normalizedSymbol);
        FundamentalScoreResponse score = fundamentalScoreService.analyze(fundamentals);
        return new FundamentalAnalysisResponse(fundamentals, score);
    }

    @GetMapping("/{symbol}/score")
    public FundamentalScoreResponse getFundamentalScore(@PathVariable String symbol) {
        FundamentalsResponse fundamentals = screenerScraperService.fetchFundamentals(symbol.trim().toUpperCase());
        return fundamentalScoreService.analyze(fundamentals);
    }

    @GetMapping("/awards")
    public PagedResult<AwardStockResponse> getAwardStocks(
            @RequestParam(defaultValue = "1") int pageNo,
            @RequestParam String prevDate,
            @RequestParam String toDate,
            @RequestParam(defaultValue = "P") String search
    ) {
        return bseAwardStockService.fetchAwardStocks(pageNo, prevDate, toDate, search);
    }

    @GetMapping("/results/upcoming")
    public PagedResult<UpcomingResultResponse> getUpcomingResults(
            @RequestParam(defaultValue = "1") int pageNo,
            @RequestParam String prevDate,
            @RequestParam String toDate,
            @RequestParam(defaultValue = "P") String search
    ) {
        return bseResultsCalendarService.fetchUpcomingResults(pageNo, prevDate, toDate, search);
    }

    @GetMapping("/results/announced")
    public PagedResult<AnnouncedResultResponse> getAnnouncedResults(
            @RequestParam(defaultValue = "1") int pageNo,
            @RequestParam String prevDate,
            @RequestParam String toDate,
            @RequestParam(defaultValue = "P") String search
    ) {
        return bseResultsCalendarService.fetchAnnouncedResults(pageNo, prevDate, toDate, search);
    }

    /**
     * Combined view for the main search flow: full fundamentals + only the
     * technical *summary* (trend/RSI/volume signals, latest indicator values)
     * rather than the full time series — that stays on /indicators for when
     * a chart actually needs to plot it.
     */
    @GetMapping("/{symbol}/overview")
    public StockOverviewResponse getOverview(
            @PathVariable String symbol,
            @RequestParam(defaultValue = "NSE") String exchange,
            @RequestParam(defaultValue = "6mo") String range
    ) {
        String normalizedSymbol = symbol.trim().toUpperCase();

        FundamentalsResponse fundamentals = screenerScraperService.fetchFundamentals(normalizedSymbol);
        StockIndicatorResponse indicators = stockAnalysisService.getIndicators(normalizedSymbol, exchange, range);

        return new StockOverviewResponse(normalizedSymbol, fundamentals, indicators.latest());
    }

    /** AI-generated structured analysis (fundamentals + technicals, no derived scores). */
    @GetMapping("/{symbol}/ai-analysis")
    public AiAnalysisResponse getAiAnalysis(
            @PathVariable String symbol,
            @RequestParam(defaultValue = "NSE") String exchange,
            @RequestParam(defaultValue = "6mo") String range
    ) {
        String normalizedSymbol = symbol.trim().toUpperCase();
        FundamentalsResponse fundamentals = screenerScraperService.fetchFundamentals(normalizedSymbol);
        StockIndicatorResponse indicators = stockAnalysisService.getIndicators(normalizedSymbol, exchange, range);
        return geminiService.analyze(fundamentals, indicators.latest());
    }

    /** Recent headlines for the stock, via Google News RSS (best-effort, empty list on failure). */
    @GetMapping("/{symbol}/news")
    public List<NewsItem> getNews(@PathVariable String symbol) {
        String normalizedSymbol = symbol.trim().toUpperCase();
        FundamentalsResponse fundamentals = screenerScraperService.fetchFundamentals(normalizedSymbol);
        return newsService.fetchNews(fundamentals.companyName());
    }

    /** Follow-up AI chat turn, grounded in the same fundamentals + technicals. */
    @PostMapping("/{symbol}/ai-chat")
    public AiChatResponse aiChat(
            @PathVariable String symbol,
            @RequestParam(defaultValue = "NSE") String exchange,
            @RequestParam(defaultValue = "6mo") String range,
            @RequestBody AiChatRequest request
    ) {
        String normalizedSymbol = symbol.trim().toUpperCase();
        FundamentalsResponse fundamentals = screenerScraperService.fetchFundamentals(normalizedSymbol);
        StockIndicatorResponse indicators = stockAnalysisService.getIndicators(normalizedSymbol, exchange, range);
        return geminiService.chat(fundamentals, indicators.latest(), request.history(), request.message());
    }
}
