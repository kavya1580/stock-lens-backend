package com.stockdashboard.dto;

/**
 * RECONSTRUCTED — StockSearchService.loadSymbols() parses 3 comma-separated
 * columns from nse_symbols.csv into this in order: symbol, name, then a
 * third column I genuinely can't determine from the code alone. NSE's own
 * published symbol lists typically have "SERIES" (e.g. "EQ") as that third
 * column, so I've gone with that — rename if your actual CSV/record differs.
 */
public record StockSearchResult(
        String symbol,
        String name,
        String series
) {
}
