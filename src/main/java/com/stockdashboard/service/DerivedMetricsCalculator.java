package com.stockdashboard.service;

import com.stockdashboard.dto.FundamentalsResponse;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class DerivedMetricsCalculator {

    private DerivedMetricsCalculator() {
    }

    public static DerivedMetricsResult compute(FundamentalsResponse fundamentals) {
        double stockPE = ScoreUtils.parseDouble(fundamentals.stockPE());
        double industryPE = ScoreUtils.parseDouble(fundamentals.industryPE());
        double relativePE = tryRelativePE(fundamentals.relativePE(), stockPE, industryPE);

        double profitGrowth5Y = ScoreUtils.parseDouble(fundamentals.profitGrowth5Y());
        double salesGrowth5Y = ScoreUtils.parseDouble(fundamentals.salesGrowth5Y());
        double profitGrowth3Y = ScoreUtils.parseDouble(fundamentals.profitGrowth3Y());
        double operatingProfitMargin = ScoreUtils.parseDouble(fundamentals.operatingProfitMargin());
        double operatingProfitLatest = ScoreUtils.parseDouble(fundamentals.operatingProfitLatest());
        double freeCashFlow = ScoreUtils.parseDouble(fundamentals.freeCashFlow());
        double otherIncomeLatest = ScoreUtils.parseDouble(fundamentals.otherIncomeLatest());
        double cfoOpLatest = ScoreUtils.parseDouble(fundamentals.cfoToOperatingProfitLatest());

        Double peg = computePeg(stockPE, profitGrowth5Y, profitGrowth3Y);
        Double fcfMargin = computeFcfMargin(freeCashFlow, operatingProfitLatest, operatingProfitMargin);
        Double cfoConsistency = ScoreUtils.percentPositive(fundamentals.operatingCashFlowSeries());
        Double earningsStabilityIndex = computeEarningsStabilityIndex(fundamentals.epsQuarterly());
        Double growthQualityRatio = computeGrowthQualityRatio(profitGrowth5Y, salesGrowth5Y);
        Double otherIncomeDependency = computeOtherIncomeDependency(otherIncomeLatest, operatingProfitLatest);
        PromoterTrend promoterTrend = computePromoterTrend(fundamentals.promoterHoldingQuarterly());
        InstitutionalTrend institutionalTrend = computeInstitutionalTrend(fundamentals.fiiHoldingQuarterly(), fundamentals.diiHoldingQuarterly());

        Map<String, Object> derivedMetrics = new LinkedHashMap<>();
        derivedMetrics.put("relativePE", ScoreUtils.safeDouble(relativePE));
        derivedMetrics.put("peg", ScoreUtils.safeNullable(peg));
        derivedMetrics.put("fcfMargin", ScoreUtils.safeNullable(fcfMargin));
        derivedMetrics.put("cfoConsistency", ScoreUtils.safeNullable(cfoConsistency));
        derivedMetrics.put("earningsStabilityIndex", ScoreUtils.safeNullable(earningsStabilityIndex));
        derivedMetrics.put("growthQualityRatio", ScoreUtils.safeNullable(growthQualityRatio));
        derivedMetrics.put("otherIncomeDependencyRatio", ScoreUtils.safeNullable(otherIncomeDependency));
        derivedMetrics.put("promoterTrend", promoterTrend.label());
        derivedMetrics.put("promoterTrendSlope", ScoreUtils.safeNullable(promoterTrend.slope()));
        derivedMetrics.put("institutionalAccumulationTrend", institutionalTrend.label());
        derivedMetrics.put("institutionalTrendSlope", ScoreUtils.safeNullable(institutionalTrend.slope()));

        return new DerivedMetricsResult(
                derivedMetrics,
                relativePE,
                peg,
                fcfMargin,
                cfoConsistency,
                earningsStabilityIndex,
                growthQualityRatio,
                otherIncomeDependency,
                cfoOpLatest,
                promoterTrend.slope(),
                promoterTrend.label(),
                institutionalTrend.slope(),
                institutionalTrend.label(),
                freeCashFlow
        );
    }

    private static double tryRelativePE(String relativePEStr, double stockPE, double industryPE) {
        double relativePE = ScoreUtils.parseDouble(relativePEStr);
        if (ScoreUtils.isValid(relativePE)) {
            return relativePE;
        }
        if (ScoreUtils.isValid(stockPE) && ScoreUtils.isValid(industryPE) && industryPE != 0) {
            return stockPE / industryPE;
        }
        return Double.NaN;
    }

    private static Double computePeg(double stockPE, double profitGrowth5Y, double profitGrowth3Y) {
        if (ScoreUtils.isValid(stockPE) && ScoreUtils.isValid(profitGrowth5Y) && profitGrowth5Y > 0) {
            return stockPE / profitGrowth5Y;
        }
        if (ScoreUtils.isValid(stockPE) && ScoreUtils.isValid(profitGrowth3Y) && profitGrowth3Y > 0) {
            return stockPE / profitGrowth3Y;
        }
        return null;
    }

    private static Double computeFcfMargin(double freeCashFlow, double operatingProfitLatest, double operatingProfitMargin) {
        if (!ScoreUtils.isValid(freeCashFlow) || !ScoreUtils.isValid(operatingProfitLatest) || !ScoreUtils.isValid(operatingProfitMargin) || operatingProfitMargin == 0) {
            return null;
        }
        double impliedSales = operatingProfitLatest / (operatingProfitMargin / 100.0);
        if (!ScoreUtils.isValid(impliedSales) || impliedSales == 0) {
            return null;
        }
        return freeCashFlow / impliedSales;
    }

    private static Double computeEarningsStabilityIndex(Map<String, String> epsSeries) {
        List<Double> values = ScoreUtils.parseSeriesValues(epsSeries);
        if (values.isEmpty()) {
            return null;
        }
        double mean = values.stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN);
        if (!ScoreUtils.isValid(mean) || mean == 0) {
            return null;
        }
        double variance = values.stream()
                .mapToDouble(v -> Math.pow(v - mean, 2))
                .average()
                .orElse(0.0);
        double stddev = Math.sqrt(variance);
        return stddev / Math.abs(mean);
    }

    private static Double computeGrowthQualityRatio(double profitGrowth5Y, double salesGrowth5Y) {
        if (!ScoreUtils.isValid(profitGrowth5Y) || !ScoreUtils.isValid(salesGrowth5Y) || salesGrowth5Y == 0) {
            return null;
        }
        return profitGrowth5Y / salesGrowth5Y;
    }

    private static Double computeOtherIncomeDependency(double otherIncomeLatest, double operatingProfitLatest) {
        if (!ScoreUtils.isValid(otherIncomeLatest) || !ScoreUtils.isValid(operatingProfitLatest) || operatingProfitLatest == 0) {
            return null;
        }
        return Math.abs(otherIncomeLatest) / Math.abs(operatingProfitLatest);
    }

    private static PromoterTrend computePromoterTrend(Map<String, String> promoterSeries) {
        List<Double> values = ScoreUtils.parseSeriesValues(promoterSeries);
        double slope = ScoreUtils.linearSlope(values);
        String label;
        if (!ScoreUtils.isValid(slope)) {
            label = "Unknown";
        } else if (slope > 0.5) {
            label = "Rising";
        } else if (slope >= -0.1) {
            label = "Stable";
        } else if (slope >= -0.5) {
            label = "Declining";
        } else {
            label = "Sharply Declining";
        }
        return new PromoterTrend(slope, label);
    }

    private static InstitutionalTrend computeInstitutionalTrend(Map<String, String> fiiSeries, Map<String, String> diiSeries) {
        List<Double> fiiValues = ScoreUtils.parseSeriesValues(fiiSeries);
        List<Double> diiValues = ScoreUtils.parseSeriesValues(diiSeries);
        int size = Math.min(fiiValues.size(), diiValues.size());
        if (size == 0) {
            return new InstitutionalTrend(Double.NaN, "Unknown");
        }
        List<Double> combined = ScoreUtils.combineSeries(fiiValues, diiValues, size);
        double slope = ScoreUtils.linearSlope(combined);
        String label;
        if (!ScoreUtils.isValid(slope)) {
            label = "Unknown";
        } else if (slope > 0.2) {
            label = "Accumulating";
        } else if (slope >= -0.1) {
            label = "Flat";
        } else {
            label = "Distributing";
        }
        return new InstitutionalTrend(slope, label);
    }

    public static final class DerivedMetricsResult {
        private final Map<String, Object> derivedMetrics;
        private final double relativePE;
        private final Double peg;
        private final Double fcfMargin;
        private final Double cfoConsistency;
        private final Double earningsStabilityIndex;
        private final Double growthQualityRatio;
        private final Double otherIncomeDependencyRatio;
        private final double cfoOpLatest;
        private final double promoterSlope;
        private final String promoterTrend;
        private final double institutionalSlope;
        private final String institutionalAccumulationTrend;
        private final double freeCashFlow;

        public DerivedMetricsResult(
                Map<String, Object> derivedMetrics,
                double relativePE,
                Double peg,
                Double fcfMargin,
                Double cfoConsistency,
                Double earningsStabilityIndex,
                Double growthQualityRatio,
                Double otherIncomeDependencyRatio,
                double cfoOpLatest,
                double promoterSlope,
                String promoterTrend,
                double institutionalSlope,
                String institutionalAccumulationTrend,
                double freeCashFlow
        ) {
            this.derivedMetrics = derivedMetrics;
            this.relativePE = relativePE;
            this.peg = peg;
            this.fcfMargin = fcfMargin;
            this.cfoConsistency = cfoConsistency;
            this.earningsStabilityIndex = earningsStabilityIndex;
            this.growthQualityRatio = growthQualityRatio;
            this.otherIncomeDependencyRatio = otherIncomeDependencyRatio;
            this.cfoOpLatest = cfoOpLatest;
            this.promoterSlope = promoterSlope;
            this.promoterTrend = promoterTrend;
            this.institutionalSlope = institutionalSlope;
            this.institutionalAccumulationTrend = institutionalAccumulationTrend;
            this.freeCashFlow = freeCashFlow;
        }

        public Map<String, Object> derivedMetrics() {
            return derivedMetrics;
        }

        public double relativePE() {
            return relativePE;
        }

        public Double peg() {
            return peg;
        }

        public Double fcfMargin() {
            return fcfMargin;
        }

        public Double cfoConsistency() {
            return cfoConsistency;
        }

        public Double earningsStabilityIndex() {
            return earningsStabilityIndex;
        }

        public Double growthQualityRatio() {
            return growthQualityRatio;
        }

        public Double otherIncomeDependencyRatio() {
            return otherIncomeDependencyRatio;
        }

        public double cfoOpLatest() {
            return cfoOpLatest;
        }

        public double promoterSlope() {
            return promoterSlope;
        }

        public String promoterTrend() {
            return promoterTrend;
        }

        public double institutionalSlope() {
            return institutionalSlope;
        }

        public String institutionalAccumulationTrend() {
            return institutionalAccumulationTrend;
        }

        public double freeCashFlow() {
            return freeCashFlow;
        }
    }

    private record PromoterTrend(double slope, String label) {
    }

    private record InstitutionalTrend(double slope, String label) {
    }
}
