package com.stockdashboard.dto;

public record StockSearchResult(
        String symbol,
        String name,
        String series
) {
}
