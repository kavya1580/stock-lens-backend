package com.stockdashboard.dto;

import java.util.List;

/** RECONSTRUCTED — matches IndicatorService.macd()'s return usage exactly. */
public record MacdResult(
        List<Double> macdLine,
        List<Double> signalLine,
        List<Double> histogram
) {
}
