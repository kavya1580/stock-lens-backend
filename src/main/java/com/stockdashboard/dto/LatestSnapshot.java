package com.stockdashboard.dto;

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
