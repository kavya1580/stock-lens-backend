package com.stockdashboard.dto;

/**
 * RECONSTRUCTED — field order matches the constructor call in
 * StockAnalysisService.buildLatestSnapshot() exactly. One genuine guess:
 * the 6th constructor arg is always passed as a literal `null` in that
 * method (ema20 is computed elsewhere but never plugged in here) — I've
 * named it `ema20` as the most likely intent, but verify against your
 * actual record if you have it.
 */
public record LatestSnapshot(
        Double close,
        Double change,
        Double changePercent,
        Double sma20,
        Double sma50,
        Double ema20,
        Double rsi,
        Double macd,
        Double macdSignal,
        Double bollingerUpper,
        Double bollingerLower,
        Long volume,
        Double avgVolume,
        String trendSignal,
        String rsiSignal,
        String volumeSignal
) {
}
