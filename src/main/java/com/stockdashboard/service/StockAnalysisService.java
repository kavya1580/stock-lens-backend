package com.stockdashboard.service;

import com.stockdashboard.dto.*;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class StockAnalysisService {

    private final YahooFinanceService yahooFinanceService;
    private final IndicatorService indicatorService;

    public StockAnalysisService(YahooFinanceService yahooFinanceService, IndicatorService indicatorService) {
        this.yahooFinanceService = yahooFinanceService;
        this.indicatorService = indicatorService;
    }

    @Cacheable(value = "stockIndicators", key = "#symbol + '-' + #exchange + '-' + #range")
    public StockIndicatorResponse getIndicators(String symbol, String exchange, String range) {
        List<OhlcvBar> bars = yahooFinanceService.fetchDailyBars(symbol, exchange, range);
        List<Double> closes = bars.stream().map(OhlcvBar::close).toList();

        List<Double> sma20 = indicatorService.sma(closes, 20);
        List<Double> sma50 = indicatorService.sma(closes, 50);
        List<Double> ema20 = indicatorService.ema(closes, 20);
        List<Double> rsi14 = indicatorService.rsi(closes, 14);
        MacdResult macd = indicatorService.macd(closes, 12, 26, 9);
        BollingerResult bollinger = indicatorService.bollinger(closes, 20, 2.0);
        List<Double> avgVolume20 = indicatorService.avgVolume(bars, 20);

        LatestSnapshot latest = buildLatestSnapshot(bars, sma20, sma50, rsi14, macd, bollinger, avgVolume20);

        return new StockIndicatorResponse(
                symbol.toUpperCase(),
                exchange.toUpperCase(),
                bars,
                sma20,
                sma50,
                ema20,
                rsi14,
                macd,
                bollinger,
                avgVolume20,
                latest
        );
    }

    private LatestSnapshot buildLatestSnapshot(
            List<OhlcvBar> bars,
            List<Double> sma20,
            List<Double> sma50,
            List<Double> rsi14,
            MacdResult macd,
            BollingerResult bollinger,
            List<Double> avgVolume20
    ) {
        int last = bars.size() - 1;
        int prev = Math.max(last - 1, 0);

        double close = bars.get(last).close();
        double prevClose = bars.get(prev).close();
        double change = close - prevClose;
        double changePercent = prevClose != 0 ? (change / prevClose) * 100 : 0;

        Double curSma20 = sma20.get(last);
        Double curSma50 = sma50.get(last);
        Double curRsi = rsi14.get(last);
        Double curMacd = macd.macdLine().get(last);
        Double curSignal = macd.signalLine().get(last);
        Long volume = bars.get(last).volume();
        Double curAvgVolume = avgVolume20.get(last);

        String trendSignal = "Neutral";
        if (curSma20 != null && curSma50 != null) {
            if (close > curSma20 && curSma20 > curSma50) trendSignal = "Bullish";
            else if (close < curSma20 && curSma20 < curSma50) trendSignal = "Bearish";
        }

        String rsiSignal = "Neutral";
        if (curRsi != null) {
            if (curRsi >= 70) rsiSignal = "Overbought";
            else if (curRsi <= 30) rsiSignal = "Oversold";
        }

        String volumeSignal = "Neutral";
        if (volume != null && curAvgVolume != null && curAvgVolume > 0) {
            volumeSignal = volume > curAvgVolume ? "Above Average" : "Below Average";
        }

        return new LatestSnapshot(
                round(close),
                round(change),
                round(changePercent),
                curSma20,
                curSma50,
                null,
                curRsi,
                curMacd,
                curSignal,
                bollinger.upper().get(last),
                bollinger.lower().get(last),
                volume,
                curAvgVolume,
                trendSignal,
                rsiSignal,
                volumeSignal
        );
    }

    private double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
