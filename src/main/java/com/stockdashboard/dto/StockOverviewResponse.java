package com.stockdashboard.dto;

/**
 * Response for GET /api/stocks/{symbol}/overview — fundamentals in full,
 * technicals trimmed down to just the latest snapshot (no bars/series).
 */
public record StockOverviewResponse(
        String symbol,
        FundamentalsResponse fundamentals,
        LatestSnapshot technicalSummary
) {
}
