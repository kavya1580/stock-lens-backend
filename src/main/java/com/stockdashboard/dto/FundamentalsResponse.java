package com.stockdashboard.dto;

import java.util.List;
import java.util.Map;

/**
 * Fundamentals snapshot for a single company, scraped from Screener.in.
 *
 * Field groups (in order):
 *  1. Identity & top ratios
 *  2. Growth (sales/profit, now with 10Y/3Y/TTM alongside 5Y)
 *  3. Shareholding (latest + quarterly series)
 *  4. Balance sheet / leverage
 *  5. Cash flow (incl. CFO/OP — the real earnings-quality ratio)
 *  6. Profitability & efficiency ratios
 *  7. Working capital (debtor/inventory/payable days, cash conversion cycle)
 *  8. Time series for charting
 *  9. NEW: sector classification, return-ratio trend ranges, tax rate trend,
 *     other-income trend, shareholder count, and Screener's own pros/cons.
 */
public record FundamentalsResponse(
        // ── Identity & top ratios ───────────────────────────────────────────
        String symbol,
        String companyName,
        String marketCap,
        String currentPrice,
        String changePercent,
        String stockPE,
        String relativePE,
        String industryPE,
        String pbRatio,
        String evEbitda,
        String bookValue,
        String dividendYield,
        String roce,
        String roe,
        String roa,
        String faceValue,

        // ── Growth ───────────────────────────────────────────────────────
        String salesGrowth3Y,
        String salesGrowth5Y,
        String profitGrowth3Y,
        String profitGrowth5Y,

        // ── Shareholding ─────────────────────────────────────────────────
        String promoterHolding,
        String fiiHolding,
        String diiHolding,
        String publicHolding,

        // ── Balance sheet / leverage ─────────────────────────────────────
        String borrowings,
        String reserves,
        String debtToEquity,

        // ── Cash flow ────────────────────────────────────────────────────
        String operatingCashFlow,
        String freeCashFlow,
        String netCashFlow,

        // ── Profitability & efficiency ───────────────────────────────────
        String eps,
        String operatingProfitMargin,
        String netProfitMargin,
        String currentRatio,
        String interestCoverage,
        String assetTurnover,
        String inventoryTurnover,
        String receivableDays,
        String workingCapitalDays,

        // ── Time series (for charts) ─────────────────────────────────────
        Map<String, String> operatingCashFlowSeries,
        Map<String, String> freeCashFlowSeries,
        Map<String, String> netCashFlowSeries,
        Map<String, String> opmQuarterly,
        Map<String, String> epsQuarterly,
        Map<String, String> salesQuarterly,
        Map<String, String> netProfitQuarterly,
        Map<String, String> promoterHoldingQuarterly,
        Map<String, String> fiiHoldingQuarterly,
        Map<String, String> diiHoldingQuarterly,
        Map<String, String> publicHoldingQuarterly,

        // ── NEW: sector classification ───────────────────────────────────
        // Lets the frontend branch scoring (e.g. skip D/E penalty for BFSI).
        SectorInfo sector,

        // ── NEW: return-ratio trend ranges (10Y / 5Y / 3Y / latest) ──────
        // Single point-in-time ROE/ROCE hides multi-year recovery/decline
        // stories (Lupin's ROCE went 40% -> -7% -> 30% over a decade).
        RatioRange roceRange,
        RatioRange roeRange,
        RatioRange salesGrowthRange,
        RatioRange profitGrowthRange,
        RatioRange stockPriceCagrRange,

        // ── NEW: real earnings-quality signal ────────────────────────────
        // CFO/OP = cash generated from operations vs accounting operating
        // profit. Persistently low/falling values are a genuine red flag
        // that sales/profit-growth ratios alone can't catch.
        String cfoToOperatingProfitLatest,
        Map<String, String> cfoToOperatingProfitSeries,

        // ── NEW: working capital detail ───────────────────────────────────
        String debtorDays,
        String inventoryDays,
        String daysPayable,
        String cashConversionCycle,

        // ── NEW: tax rate trend (flags one-off profit boosts) ────────────
        String taxRateLatest,
        Map<String, String> taxRateSeries,

        // ── NEW: other income trend (flags non-operating profit inflation) ─
        String otherIncomeLatest,
        Map<String, String> otherIncomeSeries,
        String operatingProfitLatest, // needed as denominator for the ratio above

        // ── NEW: dividend payout sustainability & promoter pledge ────────
        // Payout ratio (not just yield) shows whether dividends are backed by
        // profit; pledge is best-effort since Screener frequently omits it.
        String dividendPayoutLatest,
        Map<String, String> dividendPayoutSeries,
        String promoterPledge,

        // ── NEW: ownership breadth ────────────────────────────────────────
        String shareholderCount,

        // ── NEW: Screener's own rule-based checklist ─────────────────────
        // Shown as a secondary sanity-check alongside your own scores.
        List<String> pros,
        List<String> cons,

        String sourceUrl
) {
    public record SectorInfo(
            String broadSector,
            String sector,
            String broadIndustry,
            String industry
    ) {
        public boolean isFinancials() {
            String combined = (safe(broadSector) + " " + safe(sector) + " " + safe(broadIndustry) + " " + safe(industry))
                    .toLowerCase();
            return combined.contains("bank") || combined.contains("financ") ||
                    combined.contains("nbfc") || combined.contains("insurance");
        }

        private static String safe(String s) {
            return s == null ? "" : s;
        }
    }

    /** A metric measured over multiple horizons, e.g. ROE at 10Y/5Y/3Y/latest. */
    public record RatioRange(
            String tenYear,
            String fiveYear,
            String threeYear,
            String latestOrTTM // "Last Year" for ROE/ROCE, "TTM" for growth, "1 Year" for price CAGR
    ) {}
}