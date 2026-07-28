package com.stockdashboard.dto;

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
