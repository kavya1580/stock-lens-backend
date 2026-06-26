package com.stockdashboard.controller;

import com.stockdashboard.dto.FundamentalScoreResponse;
import com.stockdashboard.dto.FundamentalsResponse;
import com.stockdashboard.dto.StockIndicatorResponse;
import com.stockdashboard.dto.StockOverviewResponse;
import com.stockdashboard.dto.StockSearchResult;
import com.stockdashboard.service.FundamentalScoreService;
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

    public StockController(
            StockSearchService stockSearchService,
            StockAnalysisService stockAnalysisService,
            FundamentalScoreService fundamentalScoreService,
            ScreenerScraperService screenerScraperService
    ) {
        this.stockSearchService = stockSearchService;
        this.stockAnalysisService = stockAnalysisService;
        this.fundamentalScoreService = fundamentalScoreService;
        this.screenerScraperService = screenerScraperService;
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
    public FundamentalScoreResponse getFundamentalAnalysis(@PathVariable String symbol) {
        FundamentalsResponse fundamentals = screenerScraperService.fetchFundamentals(symbol.trim().toUpperCase());
        return fundamentalScoreService.analyze(fundamentals);
    }

    @GetMapping("/{symbol}/score")
    public FundamentalScoreResponse getFundamentalScore(@PathVariable String symbol) {
        FundamentalsResponse fundamentals = screenerScraperService.fetchFundamentals(symbol.trim().toUpperCase());
        return fundamentalScoreService.analyze(fundamentals);
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
}
