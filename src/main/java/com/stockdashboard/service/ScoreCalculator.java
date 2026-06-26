package com.stockdashboard.service;

import com.stockdashboard.dto.FundamentalsResponse;
import com.stockdashboard.dto.GreenFlag;
import com.stockdashboard.dto.RedFlag;
import com.stockdashboard.dto.ScoreBreakdown;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ScoreCalculator {

    private static final double PROFITABILITY_WEIGHT = 16.0;
    private static final double GROWTH_WEIGHT = 16.0;
    private static final double FINANCIAL_HEALTH_WEIGHT = 13.0;
    private static final double CASH_FLOW_QUALITY_WEIGHT = 13.0;
    private static final double EARNINGS_QUALITY_WEIGHT = 10.0;
    private static final double VALUATION_WEIGHT = 10.0;
    private static final double MOMENTUM_WEIGHT = 8.0;
    private static final double RISK_WEIGHT = 8.0;
    private static final double OWNERSHIP_WEIGHT = 4.0;
    private static final double EFFICIENCY_WEIGHT = 2.0;

    private ScoreCalculator() {
    }

    public static ScoreAnalysis analyze(FundamentalsResponse fundamentals) {
        DerivedMetricsCalculator.DerivedMetricsResult derived = DerivedMetricsCalculator.compute(fundamentals);

        List<ScoreBreakdown> breakdowns = new ArrayList<>();
        breakdowns.add(scoreProfitability(fundamentals));
        breakdowns.add(scoreGrowth(fundamentals));
        breakdowns.add(scoreFinancialHealth(fundamentals));
        breakdowns.add(scoreCashFlowQuality(fundamentals));
        breakdowns.add(scoreEarningsQuality(fundamentals));
        breakdowns.add(scoreValuation(fundamentals, derived));
        breakdowns.add(scoreMomentum(fundamentals));
        breakdowns.add(scoreRisk(fundamentals));
        breakdowns.add(scoreOwnership(fundamentals, derived));
        breakdowns.add(scoreEfficiency(fundamentals));

        double weightedSum = 0.0;
        double weightSum = 0.0;
        int computedScores = 0;
        for (ScoreBreakdown breakdown : breakdowns) {
            if (breakdown.score() != null) {
                computedScores++;
                weightedSum += breakdown.score() * breakdown.weight();
                weightSum += breakdown.weight();
            }
        }

        Double finalScore = weightSum == 0.0 ? null : ScoreUtils.roundOneDecimal(weightedSum / weightSum);
        String rating = finalScore == null ? "Insufficient Data" : mapRating(finalScore);
        String dataConfidence = computedScores + "/10";

        List<RedFlag> redFlags = buildRedFlags(fundamentals, derived);
        List<GreenFlag> greenFlags = buildGreenFlags(fundamentals, derived);

        return new ScoreAnalysis(breakdowns, finalScore, rating, dataConfidence, redFlags, greenFlags, derived.derivedMetrics());
    }

    private static ScoreBreakdown scoreProfitability(FundamentalsResponse f) {
        Map<String, Double> scores = new LinkedHashMap<>();
        Map<String, Object> metrics = new LinkedHashMap<>();

        double roe = ScoreUtils.parseDouble(f.roe());
        double roce = ScoreUtils.parseDouble(f.roce());
        double roa = ScoreUtils.parseDouble(f.roa());
        double opm = ScoreUtils.parseDouble(f.operatingProfitMargin());
        double npm = ScoreUtils.parseDouble(f.netProfitMargin());

        boolean isFinancial = f.sector() != null && f.sector().isFinancials();

        if (ScoreUtils.isValid(roe)) {
            scores.put("roe", ScoreUtils.scoreROE(roe));
            metrics.put("roe", roe);
        }
        if (ScoreUtils.isValid(roce)) {
            scores.put("roce", ScoreUtils.scoreROCE(roce));
            metrics.put("roce", roce);
        }
        if (ScoreUtils.isValid(roa)) {
            scores.put("roa", ScoreUtils.scoreROA(roa));
            metrics.put("roa", roa);
        }
        if (!isFinancial && ScoreUtils.isValid(opm)) {
            scores.put("opm", ScoreUtils.scoreOPM(opm));
            metrics.put("operatingProfitMargin", opm);
        }
        if (!isFinancial && ScoreUtils.isValid(npm)) {
            scores.put("npm", ScoreUtils.scoreNPM(npm));
            metrics.put("netProfitMargin", npm);
        }

        Map<String, Double> weights = new LinkedHashMap<>();
        if (isFinancial) {
            weights.put("roe", 0.38333);
            weights.put("roce", 0.38333);
            weights.put("roa", 0.23333);
        } else {
            weights.put("roe", 0.30);
            weights.put("roce", 0.30);
            weights.put("roa", 0.15);
            weights.put("opm", 0.15);
            weights.put("npm", 0.10);
        }

        Double score = ScoreUtils.weightedScore(scores, weights);
        String explanation = "Profitability evaluates return ratios and margins" + (isFinancial ? " for a financial-sector company." : ".");
        if (score == null) {
            explanation = "Profitability score could not be computed due to missing return-ratio data.";
        }

        return new ScoreBreakdown("Profitability Score", score, PROFITABILITY_WEIGHT, explanation, metrics);
    }

    private static ScoreBreakdown scoreGrowth(FundamentalsResponse f) {
        Map<String, Double> scores = new LinkedHashMap<>();
        Map<String, Object> metrics = new LinkedHashMap<>();

        double salesGrowth5Y = ScoreUtils.parseDouble(f.salesGrowth5Y());
        double salesGrowthTTM = ScoreUtils.parseDouble(f.salesGrowthRange().latestOrTTM());
        double profitGrowth5Y = ScoreUtils.parseDouble(f.profitGrowth5Y());
        double profitGrowthTTM = ScoreUtils.parseDouble(f.profitGrowthRange().latestOrTTM());
        double salesGrowth3Y = ScoreUtils.parseDouble(f.salesGrowth3Y());
        Double epsTrend = calculateEpsTrend(f.epsQuarterly());
        Double salesTrend = calculateGrowthTrend(salesGrowth3Y, salesGrowth5Y);

        if (ScoreUtils.isValid(salesGrowth5Y)) {
            scores.put("salesGrowth5Y", ScoreUtils.scoreGrowth(salesGrowth5Y));
            metrics.put("salesGrowth5Y", salesGrowth5Y);
        }
        if (ScoreUtils.isValid(salesGrowthTTM)) {
            scores.put("salesGrowthTTM", ScoreUtils.scoreGrowth(salesGrowthTTM));
            metrics.put("salesGrowthRangeTTM", salesGrowthTTM);
        }
        if (ScoreUtils.isValid(profitGrowth5Y)) {
            scores.put("profitGrowth5Y", ScoreUtils.scoreGrowth(profitGrowth5Y));
            metrics.put("profitGrowth5Y", profitGrowth5Y);
        }
        if (ScoreUtils.isValid(profitGrowthTTM)) {
            scores.put("profitGrowthTTM", ScoreUtils.scoreGrowth(profitGrowthTTM));
            metrics.put("profitGrowthRangeTTM", profitGrowthTTM);
        }
        if (salesTrend != null && ScoreUtils.isValid(salesTrend)) {
            scores.put("salesGrowthTrend", ScoreUtils.scoreGrowthAcceleration(salesTrend, 0.0));
            metrics.put("salesGrowthTrend", salesTrend);
        }
        if (epsTrend != null && ScoreUtils.isValid(epsTrend)) {
            scores.put("epsQuarterlyTrend", epsTrend);
            metrics.put("epsQuarterlyTrend", epsTrend);
        }

        Map<String, Double> weights = Map.of(
                "salesGrowth5Y", 0.25,
                "salesGrowthTTM", 0.20,
                "profitGrowth5Y", 0.20,
                "profitGrowthTTM", 0.15,
                "salesGrowthTrend", 0.10,
                "epsQuarterlyTrend", 0.10
        );

        Double score = ScoreUtils.weightedScore(scores, weights);
        String explanation = "Growth uses historical and recent sales/profit momentum plus EPS trend.";
        if (score == null) {
            explanation = "Growth score could not be computed due to missing growth series or trend data.";
        }

        return new ScoreBreakdown("Growth Score", score, GROWTH_WEIGHT, explanation, metrics);
    }

    private static ScoreBreakdown scoreFinancialHealth(FundamentalsResponse f) {
        Map<String, Double> scores = new LinkedHashMap<>();
        Map<String, Object> metrics = new LinkedHashMap<>();

        double debtToEquity = ScoreUtils.parseDouble(f.debtToEquity());
        double interestCoverage = ScoreUtils.parseDouble(f.interestCoverage());
        double currentRatio = ScoreUtils.parseDouble(f.currentRatio());
        double borrowings = ScoreUtils.parseDouble(f.borrowings());
        double reserves = ScoreUtils.parseDouble(f.reserves());

        boolean isFinancial = f.sector() != null && f.sector().isFinancials();

        if (!isFinancial && ScoreUtils.isValid(debtToEquity)) {
            scores.put("debtToEquity", ScoreUtils.scoreDebt(debtToEquity));
            metrics.put("debtToEquity", debtToEquity);
        }
        if (ScoreUtils.isValid(interestCoverage)) {
            scores.put("interestCoverage", ScoreUtils.scoreInterestCoverage(interestCoverage));
            metrics.put("interestCoverage", interestCoverage);
        }
        if (ScoreUtils.isValid(currentRatio)) {
            scores.put("currentRatio", ScoreUtils.scoreCurrentRatio(currentRatio));
            metrics.put("currentRatio", currentRatio);
        }
        if (ScoreUtils.isValid(borrowings) && ScoreUtils.isValid(reserves) && reserves != 0) {
            scores.put("leverageTrend", ScoreUtils.scoreLeverageTrend(borrowings, reserves));
            metrics.put("borrowings", borrowings);
            metrics.put("reserves", reserves);
        }

        Map<String, Double> weights = new LinkedHashMap<>();
        if (isFinancial) {
            weights.put("interestCoverage", 0.50);
            weights.put("currentRatio", 0.35);
            weights.put("leverageTrend", 0.15);
        } else {
            weights.put("debtToEquity", 0.40);
            weights.put("interestCoverage", 0.30);
            weights.put("currentRatio", 0.15);
            weights.put("leverageTrend", 0.15);
        }

        Double score = ScoreUtils.weightedScore(scores, weights);
        String explanation = "Financial Health measures leverage, coverage, liquidity, and leverage trend.";
        if (score == null) {
            explanation = "Financial Health score could not be computed due to missing balance-sheet or coverage metrics.";
        }

        return new ScoreBreakdown("Financial Health / Solvency Score", score, FINANCIAL_HEALTH_WEIGHT, explanation, metrics);
    }

    private static ScoreBreakdown scoreCashFlowQuality(FundamentalsResponse f) {
        Map<String, Double> scores = new LinkedHashMap<>();
        Map<String, Object> metrics = new LinkedHashMap<>();

        double cfoOpLatest = ScoreUtils.parseDouble(f.cfoToOperatingProfitLatest());
        List<Double> cfoOpSeries = ScoreUtils.parseSeriesValues(f.cfoToOperatingProfitSeries());
        List<Double> freeCashFlowSeries = ScoreUtils.parseSeriesValues(f.freeCashFlowSeries());
        List<Double> operatingCashFlowSeries = ScoreUtils.parseSeriesValues(f.operatingCashFlowSeries());
        double freeCashFlow = ScoreUtils.parseDouble(f.freeCashFlow());

        if (ScoreUtils.isValid(cfoOpLatest)) {
            scores.put("cfoToOperatingProfitLatest", ScoreUtils.scoreCFOOP(cfoOpLatest));
            metrics.put("cfoToOperatingProfitLatest", cfoOpLatest);
        }
        if (!cfoOpSeries.isEmpty()) {
            double positivePercent = ScoreUtils.percentPositive(cfoOpSeries);
            scores.put("cfoToOperatingProfitSeries", ScoreUtils.scoreCFOOPConsistency(positivePercent));
            metrics.put("cfoToOperatingProfitSeriesPositivePct", positivePercent);
        }
        if (!freeCashFlowSeries.isEmpty()) {
            scores.put("freeCashFlowSeries", ScoreUtils.scoreFCFTrend(freeCashFlowSeries));
            metrics.put("freeCashFlowSeries", freeCashFlowSeries);
        }
        if (ScoreUtils.isValid(freeCashFlow)) {
            scores.put("freeCashFlowLatest", ScoreUtils.scoreFCFLatest(freeCashFlow, freeCashFlowSeries));
            metrics.put("freeCashFlow", freeCashFlow);
        }
        if (!operatingCashFlowSeries.isEmpty()) {
            double positivePercent = ScoreUtils.percentPositive(operatingCashFlowSeries);
            scores.put("operatingCashFlowSeries", ScoreUtils.scoreOperatingCashFlowConsistency(positivePercent));
            metrics.put("operatingCashFlowSeriesPositivePct", positivePercent);
        }

        Map<String, Double> weights = Map.of(
                "cfoToOperatingProfitLatest", 0.35,
                "cfoToOperatingProfitSeries", 0.20,
                "freeCashFlowSeries", 0.20,
                "freeCashFlowLatest", 0.15,
                "operatingCashFlowSeries", 0.10
        );

        Double score = ScoreUtils.weightedScore(scores, weights);
        String explanation = "Cash Flow Quality assesses CFO conversion, free cash flow consistency, and operating cash flow quality.";
        if (score == null) {
            explanation = "Cash Flow Quality score could not be computed due to missing cash-flow metrics.";
        }

        return new ScoreBreakdown("Cash Flow Quality Score", score, CASH_FLOW_QUALITY_WEIGHT, explanation, metrics);
    }

    private static ScoreBreakdown scoreEarningsQuality(FundamentalsResponse f) {
        Map<String, Double> scores = new LinkedHashMap<>();
        Map<String, Object> metrics = new LinkedHashMap<>();

        double taxRateLatest = ScoreUtils.parseDouble(f.taxRateLatest());
        List<Double> taxRateSeries = ScoreUtils.parseSeriesValues(f.taxRateSeries());
        double otherIncomeLatest = ScoreUtils.parseDouble(f.otherIncomeLatest());
        double operatingProfitLatest = ScoreUtils.parseDouble(f.operatingProfitLatest());
        double profitGrowth5Y = ScoreUtils.parseDouble(f.profitGrowth5Y());
        double salesGrowth5Y = ScoreUtils.parseDouble(f.salesGrowth5Y());

        if (ScoreUtils.isValid(taxRateLatest)) {
            scores.put("taxRateStability", ScoreUtils.scoreTaxStability(taxRateLatest, taxRateSeries));
            metrics.put("taxRateLatest", taxRateLatest);
            metrics.put("taxRateSeries", taxRateSeries);
        }
        Double otherIncomeDependency = ScoreUtils.computeOtherIncomeDependency(otherIncomeLatest, operatingProfitLatest);
        if (otherIncomeDependency != null) {
            scores.put("otherIncomeDependency", ScoreUtils.scoreOtherIncomeDependency(otherIncomeDependency));
            metrics.put("otherIncomeDependency", otherIncomeDependency);
            metrics.put("otherIncomeLatest", otherIncomeLatest);
            metrics.put("operatingProfitLatest", operatingProfitLatest);
        }
        if (ScoreUtils.isValid(profitGrowth5Y) && ScoreUtils.isValid(salesGrowth5Y) && salesGrowth5Y != 0) {
            scores.put("profitSalesDivergence", ScoreUtils.scoreProfitSalesDivergence(profitGrowth5Y, salesGrowth5Y));
            metrics.put("profitGrowth5Y", profitGrowth5Y);
            metrics.put("salesGrowth5Y", salesGrowth5Y);
        }

        Map<String, Double> weights = Map.of(
                "taxRateStability", 0.35,
                "otherIncomeDependency", 0.30,
                "profitSalesDivergence", 0.35
        );

        Double score = ScoreUtils.weightedScore(scores, weights);
        String explanation = "Earnings Quality checks tax stability, non-operating income dependency, and profit-to-sales divergence.";
        if (score == null) {
            explanation = "Earnings Quality score could not be computed due to missing tax, income, or growth metrics.";
        }

        return new ScoreBreakdown("Earnings Quality Score", score, EARNINGS_QUALITY_WEIGHT, explanation, metrics);
    }

    private static ScoreBreakdown scoreValuation(FundamentalsResponse f, DerivedMetricsCalculator.DerivedMetricsResult derived) {
        Map<String, Double> scores = new LinkedHashMap<>();
        Map<String, Object> metrics = new LinkedHashMap<>();

        double stockPE = ScoreUtils.parseDouble(f.stockPE());
        double relativePE = derived.relativePE();
        Double peg = derived.peg();
        double pbRatio = ScoreUtils.parseDouble(f.pbRatio());
        double evEbitda = ScoreUtils.parseDouble(f.evEbitda());
        double dividendYield = ScoreUtils.parseDouble(f.dividendYield());

        if (ScoreUtils.isValid(relativePE) && relativePE > 0) {
            scores.put("relativePE", ScoreUtils.scoreRelativePE(relativePE));
            metrics.put("relativePE", relativePE);
        } else if (ScoreUtils.isValid(stockPE) && ScoreUtils.isValid(ScoreUtils.parseDouble(f.industryPE())) && ScoreUtils.parseDouble(f.industryPE()) > 0) {
            double fallbackRelativePE = stockPE / ScoreUtils.parseDouble(f.industryPE());
            if (ScoreUtils.isValid(fallbackRelativePE) && fallbackRelativePE > 0) {
                scores.put("relativePE", ScoreUtils.scoreRelativePE(fallbackRelativePE));
                metrics.put("relativePE", fallbackRelativePE);
            }
        }
        if (peg != null) {
            scores.put("peg", ScoreUtils.scorePEG(peg));
            metrics.put("peg", peg);
        }
        if (ScoreUtils.isValid(pbRatio)) {
            scores.put("pbRatio", ScoreUtils.scorePB(pbRatio));
            metrics.put("pbRatio", pbRatio);
        }
        if (ScoreUtils.isValid(evEbitda)) {
            scores.put("evEbitda", ScoreUtils.scoreEVEBITDA(evEbitda));
            metrics.put("evEbitda", evEbitda);
        }
        if (ScoreUtils.isValid(dividendYield)) {
            scores.put("dividendYield", ScoreUtils.scoreDividendYield(dividendYield));
            metrics.put("dividendYield", dividendYield);
        }

        Map<String, Double> weights = new LinkedHashMap<>();
        if (scores.containsKey("peg")) {
            weights.put("peg", 0.35);
            weights.put("relativePE", 0.25);
            weights.put("pbRatio", 0.20);
            weights.put("evEbitda", 0.15);
            weights.put("dividendYield", 0.05);
        } else {
            weights.put("relativePE", 0.50);
            weights.put("pbRatio", 0.30);
            weights.put("evEbitda", 0.15);
            weights.put("dividendYield", 0.05);
        }

        Double score = ScoreUtils.weightedScore(scores, weights);
        String explanation = "Valuation combines relative P/E, PB, EV/EBITDA, and dividend yield, using PEG if growth data is available.";
        if (score == null) {
            explanation = "Valuation score could not be computed due to missing valuation metrics.";
        }

        return new ScoreBreakdown("Valuation Score", score, VALUATION_WEIGHT, explanation, metrics);
    }

    private static ScoreBreakdown scoreMomentum(FundamentalsResponse f) {
        Map<String, Double> scores = new LinkedHashMap<>();
        Map<String, Object> metrics = new LinkedHashMap<>();

        double roceLatest = ScoreUtils.parseDouble(f.roce());
        double roceTenYear = ScoreUtils.parseDouble(f.roceRange().tenYear());
        double roeLatest = ScoreUtils.parseDouble(f.roe());
        double roeTenYear = ScoreUtils.parseDouble(f.roeRange().tenYear());
        double salesTTM = ScoreUtils.parseDouble(f.salesGrowthRange().latestOrTTM());
        double sales3Y = ScoreUtils.parseDouble(f.salesGrowthRange().threeYear());
        double profitTTM = ScoreUtils.parseDouble(f.profitGrowthRange().latestOrTTM());
        double profit3Y = ScoreUtils.parseDouble(f.profitGrowthRange().threeYear());
        double opmSlope = calculateSeriesSlope(f.opmQuarterly());

        if (ScoreUtils.isValid(roceLatest) && ScoreUtils.isValid(roceTenYear)) {
            scores.put("roceTrend", ScoreUtils.scoreTrendDirection(roceLatest, roceTenYear));
            metrics.put("roceLatest", roceLatest);
            metrics.put("roceTenYear", roceTenYear);
        }
        if (ScoreUtils.isValid(roeLatest) && ScoreUtils.isValid(roeTenYear)) {
            scores.put("roeTrend", ScoreUtils.scoreTrendDirection(roeLatest, roeTenYear));
            metrics.put("roeLatest", roeLatest);
            metrics.put("roeTenYear", roeTenYear);
        }
        if (ScoreUtils.isValid(salesTTM) && ScoreUtils.isValid(sales3Y)) {
            scores.put("salesGrowthAcceleration", ScoreUtils.scoreGrowthAcceleration(salesTTM, sales3Y));
            metrics.put("salesGrowthTTM", salesTTM);
            metrics.put("salesGrowth3Y", sales3Y);
        }
        if (ScoreUtils.isValid(profitTTM) && ScoreUtils.isValid(profit3Y)) {
            scores.put("profitGrowthAcceleration", ScoreUtils.scoreGrowthAcceleration(profitTTM, profit3Y));
            metrics.put("profitGrowthTTM", profitTTM);
            metrics.put("profitGrowth3Y", profit3Y);
        }
        if (ScoreUtils.isValid(opmSlope)) {
            scores.put("marginTrend", ScoreUtils.scoreMarginTrend(opmSlope));
            metrics.put("opmQuarterlySlope", opmSlope);
        }

        Map<String, Double> weights = Map.of(
                "roceTrend", 0.25,
                "roeTrend", 0.20,
                "salesGrowthAcceleration", 0.20,
                "profitGrowthAcceleration", 0.15,
                "marginTrend", 0.20
        );

        Double score = ScoreUtils.weightedScore(scores, weights);
        String explanation = "Momentum measures return-ratio trends, growth acceleration, and margin momentum.";
        if (score == null) {
            explanation = "Momentum score could not be computed due to missing trend data.";
        }

        return new ScoreBreakdown("Momentum of Fundamentals Score", score, MOMENTUM_WEIGHT, explanation, metrics);
    }

    private static ScoreBreakdown scoreRisk(FundamentalsResponse f) {
        Map<String, Double> scores = new LinkedHashMap<>();
        Map<String, Object> metrics = new LinkedHashMap<>();

        double debtToEquity = ScoreUtils.parseDouble(f.debtToEquity());
        double interestCoverage = ScoreUtils.parseDouble(f.interestCoverage());
        double promoterHolding = ScoreUtils.parseDouble(f.promoterHolding());
        double cashConversionCycle = ScoreUtils.parseDouble(f.cashConversionCycle());
        double operatingProfitLatest = ScoreUtils.parseDouble(f.operatingProfitLatest());
        Double otherIncomeDependency = ScoreUtils.computeOtherIncomeDependency(ScoreUtils.parseDouble(f.otherIncomeLatest()), operatingProfitLatest);
        double currentRatio = ScoreUtils.parseDouble(f.currentRatio());

        if (ScoreUtils.isValid(debtToEquity) && ScoreUtils.isValid(interestCoverage)) {
            Double leverageRisk = avgNonNull(ScoreUtils.scoreDebt(debtToEquity), ScoreUtils.scoreInterestCoverage(interestCoverage));
            if (ScoreUtils.isValid(leverageRisk)) {
                scores.put("leverageRisk", leverageRisk);
                metrics.put("debtToEquity", debtToEquity);
                metrics.put("interestCoverage", interestCoverage);
            }
        }
        if (ScoreUtils.isValid(promoterHolding)) {
            scores.put("ownershipRisk", ScoreUtils.scorePromoterHolding(promoterHolding));
            metrics.put("promoterHolding", promoterHolding);
        }
        if (otherIncomeDependency != null) {
            scores.put("earningsQualityRisk", ScoreUtils.scoreOtherIncomeDependency(otherIncomeDependency));
            metrics.put("otherIncomeDependency", otherIncomeDependency);
            metrics.put("operatingProfitLatest", operatingProfitLatest);
        }
        if (ScoreUtils.isValid(cashConversionCycle)) {
            scores.put("workingCapitalRisk", ScoreUtils.scoreCashConversionCycle(cashConversionCycle));
            metrics.put("cashConversionCycle", cashConversionCycle);
        }
        if (ScoreUtils.isValid(currentRatio)) {
            scores.put("liquidityRisk", ScoreUtils.scoreCurrentRatio(currentRatio));
            metrics.put("currentRatio", currentRatio);
        }

        Map<String, Double> weights = Map.of(
                "leverageRisk", 0.30,
                "ownershipRisk", 0.25,
                "earningsQualityRisk", 0.20,
                "workingCapitalRisk", 0.15,
                "liquidityRisk", 0.10
        );

        Double score = ScoreUtils.weightedScore(scores, weights);
        String explanation = "Risk views leverage, ownership, earnings quality, working capital and liquidity from a downside lens.";
        if (score == null) {
            explanation = "Risk score could not be computed due to missing risk-related metrics.";
        }

        return new ScoreBreakdown("Risk Score (inverse — higher = safer)", score, RISK_WEIGHT, explanation, metrics);
    }

    private static ScoreBreakdown scoreOwnership(FundamentalsResponse f, DerivedMetricsCalculator.DerivedMetricsResult derived) {
        Map<String, Double> scores = new LinkedHashMap<>();
        Map<String, Object> metrics = new LinkedHashMap<>();

        double promoterHolding = ScoreUtils.parseDouble(f.promoterHolding());
        double promoterSlope = derived.promoterSlope();
        double fiiSlope = ScoreUtils.linearSlope(ScoreUtils.parseSeriesValues(f.fiiHoldingQuarterly()));
        double diiSlope = ScoreUtils.linearSlope(ScoreUtils.parseSeriesValues(f.diiHoldingQuarterly()));
        double publicHolding = ScoreUtils.parseDouble(f.publicHolding());

        if (ScoreUtils.isValid(promoterSlope)) {
            scores.put("promoterTrend", ScoreUtils.scorePromoterTrend(promoterSlope));
            metrics.put("promoterTrendSlope", promoterSlope);
            metrics.put("promoterTrendLabel", derived.promoterTrend());
        }
        if (ScoreUtils.isValid(fiiSlope) && ScoreUtils.isValid(diiSlope)) {
            double combinedSlope = (fiiSlope + diiSlope) / 2.0;
            scores.put("institutionalTrend", ScoreUtils.scoreInstitutionalTrend(combinedSlope));
            metrics.put("fiiSlope", fiiSlope);
            metrics.put("diiSlope", diiSlope);
        }
        if (ScoreUtils.isValid(promoterHolding)) {
            scores.put("promoterLevel", ScoreUtils.scorePromoterHolding(promoterHolding));
            metrics.put("promoterHolding", promoterHolding);
        }
        if (ScoreUtils.isValid(publicHolding)) {
            scores.put("publicHolding", ScoreUtils.scoreFloatRisk(publicHolding));
            metrics.put("publicHolding", publicHolding);
        }

        Map<String, Double> weights = Map.of(
                "promoterTrend", 0.35,
                "institutionalTrend", 0.35,
                "promoterLevel", 0.20,
                "publicHolding", 0.10
        );

        Double score = ScoreUtils.weightedScore(scores, weights);
        String explanation = "Ownership & Shareholding measures promoter confidence, institutional flow, and free-float risk.";
        if (score == null) {
            explanation = "Ownership score could not be computed due to missing shareholder data.";
        }

        return new ScoreBreakdown("Ownership & Shareholding Score", score, OWNERSHIP_WEIGHT, explanation, metrics);
    }

    private static ScoreBreakdown scoreEfficiency(FundamentalsResponse f) {
        Map<String, Double> scores = new LinkedHashMap<>();
        Map<String, Object> metrics = new LinkedHashMap<>();

        double assetTurnover = ScoreUtils.parseDouble(f.assetTurnover());
        double inventoryTurnover = ScoreUtils.parseDouble(f.inventoryTurnover());
        double cashConversionCycle = ScoreUtils.parseDouble(f.cashConversionCycle());
        double workingCapitalDays = ScoreUtils.parseDouble(f.workingCapitalDays());

        if (ScoreUtils.isValid(assetTurnover)) {
            scores.put("assetTurnover", ScoreUtils.scoreAssetTurnover(assetTurnover));
            metrics.put("assetTurnover", assetTurnover);
        }
        if (ScoreUtils.isValid(inventoryTurnover)) {
            scores.put("inventoryTurnover", ScoreUtils.scoreInventoryTurnover(inventoryTurnover));
            metrics.put("inventoryTurnover", inventoryTurnover);
        }
        if (ScoreUtils.isValid(cashConversionCycle)) {
            scores.put("cashConversionCycle", ScoreUtils.scoreCashConversionCycle(cashConversionCycle));
            metrics.put("cashConversionCycle", cashConversionCycle);
        }
        if (ScoreUtils.isValid(workingCapitalDays)) {
            scores.put("workingCapitalDays", ScoreUtils.scoreWorkingCapitalDays(workingCapitalDays));
            metrics.put("workingCapitalDays", workingCapitalDays);
        }

        Map<String, Double> weights = Map.of(
                "assetTurnover", 0.30,
                "inventoryTurnover", 0.25,
                "cashConversionCycle", 0.25,
                "workingCapitalDays", 0.20
        );

        Double score = ScoreUtils.weightedScore(scores, weights);
        String explanation = "Operating Efficiency checks turnover and working capital efficiency.";
        if (score == null) {
            explanation = "Efficiency score could not be computed due to missing operational metrics.";
        }

        return new ScoreBreakdown("Operating Efficiency Score", score, EFFICIENCY_WEIGHT, explanation, metrics);
    }

    private static List<RedFlag> buildRedFlags(FundamentalsResponse f, DerivedMetricsCalculator.DerivedMetricsResult derived) {
        List<RedFlag> flags = new ArrayList<>();

        double cfoOpLatest = ScoreUtils.parseDouble(f.cfoToOperatingProfitLatest());
        if (ScoreUtils.isValid(cfoOpLatest) && cfoOpLatest < 60) {
            flags.add(new RedFlag("Low CFO/OP", "CFO/OP is below 60%, indicating weak cash conversion of operating profit."));
        }
        double interestCoverage = ScoreUtils.parseDouble(f.interestCoverage());
        if (ScoreUtils.isValid(interestCoverage) && interestCoverage < 1.5) {
            flags.add(new RedFlag("Low Interest Coverage", "Interest coverage is below 1.5x, signaling debt servicing risk."));
        }
        double currentRatio = ScoreUtils.parseDouble(f.currentRatio());
        if (ScoreUtils.isValid(currentRatio) && currentRatio < 1.0) {
            flags.add(new RedFlag("Weak Liquidity", "Current ratio is below 1.0, which may signal short-term liquidity stress."));
        }
        Double otherIncomeDependency = ScoreUtils.computeOtherIncomeDependency(ScoreUtils.parseDouble(f.otherIncomeLatest()), ScoreUtils.parseDouble(f.operatingProfitLatest()));
        if (otherIncomeDependency != null && otherIncomeDependency > 0.20) {
            flags.add(new RedFlag("High Other Income Dependency", "Other income is more than 20% of operating profit, indicating profit may be non-operating."));
        }
        double promoterSlope = derived.promoterSlope();
        if (ScoreUtils.isValid(promoterSlope) && promoterSlope < -0.1) {
            flags.add(new RedFlag("Declining Promoter Holding", "Promoter holding is declining, suggesting owner confidence may be weakening."));
        }
        List<Double> fcfSeries = ScoreUtils.parseSeriesValues(f.freeCashFlowSeries());
        if (!fcfSeries.isEmpty()) {
            long negativeCount = fcfSeries.stream().filter(v -> ScoreUtils.isValid(v) && v < 0).count();
            if (negativeCount > fcfSeries.size() / 2) {
                flags.add(new RedFlag("Negative Free Cash Flow", "Free cash flow is negative in the majority of recent years."));
            }
        }

        return flags;
    }

    private static List<GreenFlag> buildGreenFlags(FundamentalsResponse f, DerivedMetricsCalculator.DerivedMetricsResult derived) {
        List<GreenFlag> flags = new ArrayList<>();

        double cfoOpLatest = ScoreUtils.parseDouble(f.cfoToOperatingProfitLatest());
        if (ScoreUtils.isValid(cfoOpLatest) && cfoOpLatest >= 90) {
            flags.add(new GreenFlag("Strong CFO/OP", "CFO/OP is at or above 90%, indicating high-quality cash-backed earnings."));
        }
        List<Double> fcfSeries = ScoreUtils.parseSeriesValues(f.freeCashFlowSeries());
        if (!fcfSeries.isEmpty() && fcfSeries.stream().filter(v -> ScoreUtils.isValid(v) && v > 0).count() >= (fcfSeries.size() * 0.6)) {
            flags.add(new GreenFlag("Consistent Positive FCF", "Free cash flow is positive in the majority of recent years."));
        }
        double roceLatest = ScoreUtils.parseDouble(f.roce());
        double roceTenYear = ScoreUtils.parseDouble(f.roceRange().tenYear());
        if (ScoreUtils.isValid(roceLatest) && ScoreUtils.isValid(roceTenYear) && roceLatest >= roceTenYear + 10) {
            flags.add(new GreenFlag("Improving ROCE", "ROCE is meaningfully above the 10-year average, indicating improvement."));
        }
        double promoterSlope = derived.promoterSlope();
        if (ScoreUtils.isValid(promoterSlope) && promoterSlope >= 0.0) {
            flags.add(new GreenFlag("Stable / Rising Promoter Holding", "Promoter holding is stable or increasing over recent periods."));
        }
        double institutionalSlope = derived.institutionalSlope();
        if (ScoreUtils.isValid(institutionalSlope) && institutionalSlope > 0.2) {
            flags.add(new GreenFlag("Institutional Accumulation", "Institutional holding trend is positive, indicating accumulation."));
        }
        double debtToEquity = ScoreUtils.parseDouble(f.debtToEquity());
        double interestCoverage = ScoreUtils.parseDouble(f.interestCoverage());
        if (ScoreUtils.isValid(debtToEquity) && debtToEquity <= 0.5 && ScoreUtils.isValid(interestCoverage) && interestCoverage > 6.0) {
            flags.add(new GreenFlag("Strong Balance Sheet", "Low debt-to-equity combined with high interest coverage indicates strong solvency."));
        }

        return flags;
    }

    private static String mapRating(double score) {
        if (score >= 90) {
            return "Exceptional";
        }
        if (score >= 80) {
            return "Excellent";
        }
        if (score >= 70) {
            return "Strong";
        }
        if (score >= 60) {
            return "Average";
        }
        if (score >= 50) {
            return "Weak";
        }
        return "Risky";
    }

    private static Double calculateEpsTrend(Map<String, String> quarterlyEps) {
        List<Double> epsValues = ScoreUtils.parseSeriesValues(quarterlyEps);
        if (epsValues.isEmpty()) {
            return null;
        }
        int positiveMoves = 0;
        int comparisons = 0;
        for (int i = 1; i < epsValues.size(); i++) {
            double prior = epsValues.get(i - 1);
            double current = epsValues.get(i);
            if (!ScoreUtils.isValid(prior) || !ScoreUtils.isValid(current)) {
                continue;
            }
            comparisons++;
            if (current > prior) {
                positiveMoves++;
            }
        }
        if (comparisons == 0) {
            return null;
        }
        return ScoreUtils.roundOneDecimal((positiveMoves * 100.0) / comparisons);
    }

    private static Double calculateGrowthTrend(double recent, double prior) {
        if (!ScoreUtils.isValid(recent) || !ScoreUtils.isValid(prior)) {
            return null;
        }
        return recent - prior;
    }

    private static double calculateSeriesSlope(Map<String, String> series) {
        List<Double> values = ScoreUtils.parseSeriesValues(series);
        return ScoreUtils.linearSlope(values);
    }

    private static double avgNonNull(Double a, Double b) {
        if (ScoreUtils.isValid(a != null ? a : Double.NaN) && ScoreUtils.isValid(b != null ? b : Double.NaN)) {
            return (a + b) / 2.0;
        }
        if (ScoreUtils.isValid(a != null ? a : Double.NaN)) {
            return a;
        }
        if (ScoreUtils.isValid(b != null ? b : Double.NaN)) {
            return b;
        }
        return Double.NaN;
    }
}
