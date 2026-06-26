package com.stockdashboard.dto;

/**
 * RECONSTRUCTED — inferred from YahooFinanceService.parseBars(). Field order
 * matches the constructor call there exactly. If your real OhlcvBar differs,
 * keep yours; nothing else in this project needs more than these 7 fields.
 */
public record OhlcvBar(
        long timestamp,
        String date,
        Double open,
        Double high,
        Double low,
        Double close,
        Long volume
) {
}
