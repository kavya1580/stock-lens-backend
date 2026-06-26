package com.stockdashboard.dto;

import java.util.List;

/** RECONSTRUCTED — matches the constructor call in StockAnalysisService.getIndicators() exactly. */
public record StockIndicatorResponse(
        String symbol,
        String exchange,
        List<OhlcvBar> bars,
        List<Double> sma20,
        List<Double> sma50,
        List<Double> ema20,
        List<Double> rsi14,
        MacdResult macd,
        BollingerResult bollinger,
        List<Double> avgVolume20,
        LatestSnapshot latest
) {
}
