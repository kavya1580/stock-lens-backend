package com.stockdashboard.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ScoreUtils {

    private ScoreUtils() {
    }

    public static double parseDouble(String raw) {
        if (raw == null) {
            return Double.NaN;
        }
        String cleaned = raw.replaceAll("[,\\s%]", "");
        cleaned = cleaned.replaceAll("[^0-9.\\-]", "");
        if (cleaned.isBlank() || cleaned.equals("—") || cleaned.equals("-") || cleaned.equalsIgnoreCase("n/a")) {
            return Double.NaN;
        }
        try {
            return Double.parseDouble(cleaned);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    public static boolean isMissing(String raw) {
        if (raw == null) {
            return true;
        }
        String normalized = raw.trim();
        return normalized.isEmpty() || normalized.equals("—") || normalized.equals("-") || normalized.equalsIgnoreCase("n/a");
    }

    public static boolean isValid(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }

    public static Double weightedScore(Map<String, Double> scores, Map<String, Double> weights) {
        if (scores == null || weights == null || scores.isEmpty() || weights.isEmpty()) {
            return null;
        }
        double weightedSum = 0.0;
        double weightSum = 0.0;
        for (Map.Entry<String, Double> entry : weights.entrySet()) {
            String metric = entry.getKey();
            Double weight = entry.getValue();
            if (weight == null || weight <= 0) {
                continue;
            }
            Double metricScore = scores.get(metric);
            if (!isValid(metricScore != null ? metricScore : Double.NaN)) {
                continue;
            }
            weightedSum += metricScore * weight;
            weightSum += weight;
        }
        if (weightSum == 0.0) {
            return null;
        }
        return weightedSum / weightSum;
    }

    public static Double interpolate(double value, double[][] thresholds) {
        if (!isValid(value) || thresholds == null || thresholds.length == 0) {
            return null;
        }
        double[] first = thresholds[0];
        double[] last = thresholds[thresholds.length - 1];
        if (value <= first[0]) {
            return first[1];
        }
        if (value >= last[0]) {
            return last[1];
        }
        for (int i = 1; i < thresholds.length; i++) {
            double[] lower = thresholds[i - 1];
            double[] upper = thresholds[i];
            if (value <= upper[0]) {
                double ratio = (value - lower[0]) / (upper[0] - lower[0]);
                return lower[1] + ratio * (upper[1] - lower[1]);
            }
        }
        return null;
    }

    public static List<Double> parseSeriesValues(Map<String, String> series) {
        List<Double> values = new ArrayList<>();
        if (series == null) {
            return values;
        }
        for (String raw : series.values()) {
            double parsed = parseDouble(raw);
            if (isValid(parsed)) {
                values.add(parsed);
            }
        }
        return values;
    }

    public static double linearSlope(List<Double> values) {
        if (values == null || values.size() < 2) {
            return Double.NaN;
        }
        int n = values.size();
        double first = values.get(0);
        double last = values.get(n - 1);
        if (!isValid(first) || !isValid(last)) {
            return Double.NaN;
        }
        return (last - first) / (n - 1);
    }

    public static double percentPositive(List<Double> values) {
        if (values == null || values.isEmpty()) {
            return Double.NaN;
        }
        int positive = 0;
        int count = 0;
        for (Double value : values) {
            if (isValid(value)) {
                count++;
                if (value > 0) {
                    positive++;
                }
            }
        }
        if (count == 0) {
            return Double.NaN;
        }
        return (positive * 100.0) / count;
    }

    public static double percentPositive(Map<String, String> series) {
        return percentPositive(parseSeriesValues(series));
    }

    public static List<Double> combineSeries(List<Double> a, List<Double> b, int size) {
        List<Double> combined = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            double first = i < a.size() && isValid(a.get(i)) ? a.get(i) : 0.0;
            double second = i < b.size() && isValid(b.get(i)) ? b.get(i) : 0.0;
            combined.add(first + second);
        }
        return combined;
    }

    public static double safeDouble(double value) {
        return isValid(value) ? value : 0.0;
    }

    public static Object safeNullable(Double value) {
        return value == null || !isValid(value) ? null : value;
    }

    public static Double roundOneDecimal(Double value) {
        if (value == null || !isValid(value)) {
            return null;
        }
        return Math.round(value * 10.0) / 10.0;
    }

    public static Double computeOtherIncomeDependency(double otherIncomeLatest, double operatingProfitLatest) {
        if (!isValid(otherIncomeLatest) || !isValid(operatingProfitLatest) || operatingProfitLatest == 0) {
            return null;
        }
        return Math.abs(otherIncomeLatest) / Math.abs(operatingProfitLatest);
    }

    public static Double scoreROE(double value) {
        return interpolate(value, new double[][]{
                {0, 25},
                {8, 50},
                {15, 75},
                {25, 100}
        });
    }

    public static Double scoreROCE(double value) {
        return scoreROE(value);
    }

    public static Double scoreROA(double value) {
        return interpolate(value, new double[][]{
                {0, 20},
                {4, 50},
                {8, 75},
                {15, 100}
        });
    }

    public static Double scoreOPM(double value) {
        return interpolate(value, new double[][]{
                {0, 20},
                {8, 45},
                {15, 65},
                {25, 90}
        });
    }

    public static Double scoreNPM(double value) {
        return interpolate(value, new double[][]{
                {0, 20},
                {5, 50},
                {10, 70},
                {18, 95}
        });
    }

    public static Double scoreGrowth(double value) {
        if (!isValid(value)) {
            return null;
        }
        if (value <= 0) {
            return 10.0;
        }
        return interpolate(value, new double[][]{
                {0, 30},
                {5, 45},
                {10, 65},
                {15, 80},
                {25, 100}
        });
    }

    public static Double scoreDebt(double value) {
        if (!isValid(value)) {
            return null;
        }
        if (value <= 0.3) {
            return 100.0;
        }
        if (value >= 4.0) {
            return 0.0;
        }
        return interpolate(value, new double[][]{
                {0.3, 100},
                {0.7, 80},
                {1.0, 65},
                {1.5, 40},
                {2.5, 20},
                {4.0, 0}
        });
    }

    public static Double scoreInterestCoverage(double value) {
        if (!isValid(value)) {
            return null;
        }
        if (value < 1.0) {
            return 0.0;
        }
        if (value >= 10.0) {
            return 100.0;
        }
        return interpolate(value, new double[][]{
                {1.0, 0},
                {1.5, 30},
                {3.0, 60},
                {6.0, 85},
                {10.0, 100}
        });
    }

    public static Double scoreCurrentRatio(double value) {
        if (!isValid(value)) {
            return null;
        }
        if (value < 0.8) {
            return 20.0;
        }
        if (value >= 4.0) {
            return 80.0;
        }
        return interpolate(value, new double[][]{
                {0.8, 20},
                {1.0, 45},
                {1.5, 75},
                {2.0, 90},
                {4.0, 80}
        });
    }

    public static Double scorePEG(double value) {
        if (!isValid(value) || value <= 0) {
            return null;
        }
        if (value <= 0.5) {
            return 95.0;
        }
        if (value >= 5.0) {
            return 10.0;
        }
        return interpolate(value, new double[][]{
                {0.5, 95},
                {1.0, 85},
                {1.5, 65},
                {2.0, 45},
                {5.0, 10}
        });
    }

    public static Double scoreRelativePE(double value) {
        if (!isValid(value) || value <= 0) {
            return null;
        }
        if (value <= 0.7) {
            return 90.0;
        }
        if (value >= 1.8) {
            return 20.0;
        }
        return interpolate(value, new double[][]{
                {0.7, 90},
                {1.0, 70},
                {1.3, 50},
                {1.8, 20}
        });
    }

    public static Double scorePB(double value) {
        if (!isValid(value) || value < 0) {
            return null;
        }
        if (value <= 1.0) {
            return 90.0;
        }
        if (value >= 8.0) {
            return 15.0;
        }
        return interpolate(value, new double[][]{
                {1.0, 90},
                {3.0, 65},
                {5.0, 45},
                {8.0, 15}
        });
    }

    public static Double scoreEVEBITDA(double value) {
        if (!isValid(value) || value < 0) {
            return null;
        }
        if (value <= 8.0) {
            return 90.0;
        }
        if (value >= 35.0) {
            return 15.0;
        }
        return interpolate(value, new double[][]{
                {8.0, 90},
                {15.0, 65},
                {25.0, 40},
                {35.0, 15}
        });
    }

    public static Double scoreDividendYield(double value) {
        if (!isValid(value) || value < 0) {
            return null;
        }
        if (value >= 3.0) {
            return 80.0;
        }
        if (value <= 0.0) {
            return 40.0;
        }
        return interpolate(value, new double[][]{
                {0.0, 40},
                {1.0, 55},
                {3.0, 80}
        });
    }

    public static Double scoreTaxStability(double latest, List<Double> history) {
        if (!isValid(latest)) {
            return null;
        }
        if (history == null || history.isEmpty()) {
            return 70.0;
        }
        double avg = history.stream().filter(ScoreUtils::isValid).mapToDouble(Double::doubleValue).average().orElse(Double.NaN);
        if (!isValid(avg)) {
            return 70.0;
        }
        double delta = Math.abs(latest - avg);
        if (latest < 5.0 || latest > 50.0 || delta > 15.0) {
            return 25.0;
        }
        if (delta <= 5.0 && latest >= 10.0 && latest <= 35.0) {
            return 90.0;
        }
        return 70.0;
    }

    public static Double scoreOtherIncomeDependency(double ratio) {
        if (!isValid(ratio) || ratio < 0) {
            return null;
        }
        if (ratio <= 0.05) {
            return 95.0;
        }
        if (ratio >= 0.35) {
            return 10.0;
        }
        return interpolate(ratio * 100.0, new double[][]{
                {5, 95},
                {10, 70},
                {20, 40},
                {35, 10}
        });
    }

    public static Double scoreProfitSalesDivergence(double profitGrowth5Y, double salesGrowth5Y) {
        if (!isValid(profitGrowth5Y) || !isValid(salesGrowth5Y) || salesGrowth5Y == 0) {
            return null;
        }
        double ratio = profitGrowth5Y / salesGrowth5Y;
        if (ratio >= 8) {
            return 10.0;
        }
        if (ratio <= 1.5 && ratio >= 0.5) {
            return 90.0;
        }
        if (ratio <= 0) {
            return 20.0;
        }
        return interpolate(ratio, new double[][]{
                {0.0, 20},
                {0.5, 90},
                {1.5, 90},
                {2.0, 60},
                {4.0, 30},
                {8.0, 10}
        });
    }

    public static Double scoreCashConversionCycle(double value) {
        if (!isValid(value) || value < 0) {
            return null;
        }
        if (value <= 60) {
            return 90.0;
        }
        if (value >= 300) {
            return 10.0;
        }
        return interpolate(value, new double[][]{
                {60, 90},
                {120, 60},
                {200, 35},
                {300, 10}
        });
    }

    public static Double scoreAssetTurnover(double value) {
        if (!isValid(value) || value < 0) {
            return null;
        }
        if (value >= 2.5) {
            return 90.0;
        }
        if (value <= 0.5) {
            return 30.0;
        }
        return interpolate(value, new double[][]{
                {0.5, 30},
                {1.0, 50},
                {1.7, 70},
                {2.5, 90}
        });
    }

    public static Double scoreInventoryTurnover(double value) {
        if (!isValid(value) || value < 0) {
            return null;
        }
        if (value >= 10) {
            return 90.0;
        }
        if (value <= 3) {
            return 30.0;
        }
        return interpolate(value, new double[][]{
                {3, 30},
                {6, 55},
                {10, 90}
        });
    }

    public static Double scoreWorkingCapitalDays(double value) {
        if (!isValid(value) || value < 0) {
            return null;
        }
        if (value <= 30) {
            return 90.0;
        }
        if (value >= 180) {
            return 25.0;
        }
        return interpolate(value, new double[][]{
                {30, 90},
                {90, 70},
                {180, 25}
        });
    }

    public static Double scorePromoterHolding(double value) {
        if (!isValid(value) || value < 0) {
            return null;
        }
        if (value >= 50) {
            return 90.0;
        }
        if (value <= 15) {
            return 30.0;
        }
        return interpolate(value, new double[][]{
                {15, 30},
                {25, 50},
                {40, 75},
                {50, 90}
        });
    }

    public static Double scoreFloatRisk(double value) {
        if (!isValid(value) || value < 0) {
            return null;
        }
        if (value >= 70) {
            return 40.0;
        }
        if (value <= 20) {
            return 85.0;
        }
        return interpolate(value, new double[][]{
                {20, 85},
                {50, 60},
                {70, 40}
        });
    }

    public static Double scoreTrendDirection(double latest, double baseline) {
        if (!isValid(latest) || !isValid(baseline)) {
            return null;
        }
        double delta = latest - baseline;
        if (delta >= 10) {
            return 90.0;
        }
        if (delta <= -10) {
            return 20.0;
        }
        if (Math.abs(delta) <= 5) {
            return 60.0;
        }
        if (delta > 0) {
            return interpolate(delta, new double[][]{
                    {5, 60},
                    {10, 90}
            });
        }
        return interpolate(delta, new double[][]{
                {-10, 20},
                {-5, 60}
        });
    }

    public static Double scoreGrowthAcceleration(double latest, double baseline) {
        if (!isValid(latest) || !isValid(baseline)) {
            return null;
        }
        double delta = latest - baseline;
        if (delta >= 10) {
            return 90.0;
        }
        if (delta <= -10) {
            return 20.0;
        }
        if (delta >= 0) {
            return interpolate(delta, new double[][]{
                    {0, 60},
                    {5, 75},
                    {10, 90}
            });
        }
        return interpolate(delta, new double[][]{
                {-10, 20},
                {0, 60}
        });
    }

    public static Double scoreMarginTrend(double slope) {
        if (!isValid(slope)) {
            return null;
        }
        if (slope >= 2.0) {
            return 100.0;
        }
        if (slope <= -2.0) {
            return 20.0;
        }
        return interpolate(slope, new double[][]{
                {-2.0, 20},
                {0.0, 55},
                {1.0, 80},
                {2.0, 100}
        });
    }

    public static Double scoreFCFTrend(List<Double> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        int positive = 0;
        int valid = 0;
        double last = Double.NaN;
        for (Double value : values) {
            if (!isValid(value)) {
                continue;
            }
            valid++;
            if (value > 0) {
                positive++;
            }
            last = value;
        }
        if (valid == 0) {
            return null;
        }
        double positiveRatio = positive / (double) valid;
        double baseScore = 40 + positiveRatio * 50;
        if (last > 0) {
            baseScore += 10;
        }
        return Math.min(baseScore, 100.0);
    }

    public static Double scoreFCFLatest(double freeCashFlow, List<Double> values) {
        if (!isValid(freeCashFlow)) {
            return null;
        }
        double score = freeCashFlow > 0 ? 70.0 : 20.0;
        if (values != null && values.size() >= 2) {
            Double prior = values.get(values.size() - 2);
            if (isValid(prior) && isValid(freeCashFlow)) {
                double growth = prior == 0 ? 0.0 : (freeCashFlow - prior) / Math.abs(prior) * 100.0;
                if (growth > 20) {
                    score = Math.max(score, 90.0);
                } else if (growth > 0) {
                    score = Math.max(score, 75.0);
                }
            }
        }
        return score;
    }

    public static Double scoreCFOOP(double value) {
        if (!isValid(value)) {
            return null;
        }
        if (value < 0) {
            return 10.0;
        }
        if (value > 130) {
            return 90.0;
        }
        return interpolate(value, new double[][]{
                {0, 10},
                {40, 35},
                {60, 55},
                {80, 75},
                {100, 95},
                {130, 90}
        });
    }

    public static Double scoreCFOOPConsistency(double percentPositive) {
        if (!isValid(percentPositive)) {
            return null;
        }
        if (percentPositive >= 90) {
            return 100.0;
        }
        if (percentPositive <= 25) {
            return 25.0;
        }
        return interpolate(percentPositive, new double[][]{
                {25, 25},
                {50, 55},
                {75, 80},
                {90, 100}
        });
    }

    public static Double scoreOperatingCashFlowConsistency(double percentPositive) {
        return scoreCFOOPConsistency(percentPositive);
    }

    public static Double scoreLeverageTrend(double borrowings, double reserves) {
        if (!isValid(borrowings) || !isValid(reserves) || reserves == 0) {
            return null;
        }
        double ratio = borrowings / reserves;
        if (ratio <= 0.25) {
            return 100.0;
        }
        if (ratio >= 4.0) {
            return 10.0;
        }
        return interpolate(ratio, new double[][]{
                {0.25, 100},
                {0.5, 90},
                {1.0, 65},
                {2.0, 35},
                {4.0, 10}
        });
    }

    public static Double scorePromoterTrend(double slope) {
        if (!isValid(slope)) {
            return null;
        }
        if (slope >= 0.5) {
            return 100.0;
        }
        if (slope >= 0.1) {
            return 80.0;
        }
        if (slope >= -0.1) {
            return 65.0;
        }
        if (slope >= -0.5) {
            return 40.0;
        }
        return 15.0;
    }

    public static Double scorePromoterPledge(double value) {
        if (!isValid(value) || value < 0) {
            return null;
        }
        if (value <= 0) {
            return 100.0;
        }
        if (value < 10) {
            return 70.0;
        }
        if (value <= 25) {
            return 35.0;
        }
        return 5.0;
    }

    public static Double scoreDividendPayoutSustainability(List<Double> payoutSeries) {
        if (payoutSeries == null || payoutSeries.isEmpty()) {
            return null;
        }
        long over100Count = payoutSeries.stream().filter(v -> isValid(v) && v > 100).count();
        if (over100Count >= 2) {
            return 30.0;
        }
        double avg = payoutSeries.stream().filter(ScoreUtils::isValid).mapToDouble(Double::doubleValue).average().orElse(Double.NaN);
        if (!isValid(avg)) {
            return 55.0;
        }
        double variance = payoutSeries.stream().filter(ScoreUtils::isValid)
                .mapToDouble(v -> Math.pow(v - avg, 2)).average().orElse(0.0);
        double stddev = Math.sqrt(variance);
        if (avg >= 40 && avg <= 70 && stddev < 20) {
            return 90.0;
        }
        return 55.0;
    }

    public static Double scoreInstitutionalTrend(double slope) {
        if (!isValid(slope)) {
            return null;
        }
        if (slope >= 0.2) {
            return 90.0;
        }
        if (slope >= 0.0) {
            return 65.0;
        }
        if (slope >= -0.2) {
            return 40.0;
        }
        return 15.0;
    }
}
