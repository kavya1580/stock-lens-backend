package com.stockdashboard.dto;

import java.util.List;

/** RECONSTRUCTED — matches IndicatorService.bollinger()'s return usage exactly. */
public record BollingerResult(
        List<Double> upper,
        List<Double> middle,
        List<Double> lower
) {
}
