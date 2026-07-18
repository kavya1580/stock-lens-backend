package com.stockdashboard.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * In-house "will this quarter be good?" heuristic — there's no analyst
 * consensus/estimates feed wired into this app, so this only ever compares a
 * company against its own trailing quarterly profit trend (from Screener's
 * quarterly Net Profit series), never against a real street estimate. Callers
 * must label results accordingly so they don't read as consensus beat/miss.
 */
public final class ResultExpectationCalculator {

    private static final String INSUFFICIENT_DATA = "Insufficient Data";
    private static final double ACCELERATING_THRESHOLD = 3.0;
    private static final double DECELERATING_THRESHOLD = -3.0;
    private static final double BEAT_DELTA = 8.0;
    private static final double BELOW_DELTA = -8.0;

    private ResultExpectationCalculator() {
    }

    public record ExpectationResult(String direction, String note) {
    }

    public record OutcomeResult(String priorTrendDirection, String actualVsExpected, String note) {
    }

    /** For upcoming results: what does the trailing trend suggest going in? */
    public static ExpectationResult computeExpectation(Map<String, String> netProfitQuarterly) {
        return classifyTrend(ScoreUtils.parseSeriesValues(netProfitQuarterly));
    }

    /** For announced results: compare the latest quarter against the trend that preceded it. */
    public static OutcomeResult computeOutcome(Map<String, String> netProfitQuarterly) {
        List<Double> values = ScoreUtils.parseSeriesValues(netProfitQuarterly);
        if (values.size() < 2) {
            return new OutcomeResult(INSUFFICIENT_DATA, INSUFFICIENT_DATA, "Not enough quarterly history to compare.");
        }

        List<Double> priorValues = values.subList(0, values.size() - 1);
        ExpectationResult priorTrend = classifyTrend(priorValues);
        double latestGrowth = qoqGrowth(priorValues.get(priorValues.size() - 1), values.get(values.size() - 1));
        double priorAvgGrowth = averageQoqGrowth(priorValues);

        if (!ScoreUtils.isValid(latestGrowth) || !ScoreUtils.isValid(priorAvgGrowth)) {
            return new OutcomeResult(priorTrend.direction(), INSUFFICIENT_DATA, "Not enough quarterly history to compare.");
        }

        double delta = latestGrowth - priorAvgGrowth;
        String actualVsExpected = delta >= BEAT_DELTA ? "Beat Trend" : delta <= BELOW_DELTA ? "Below Trend" : "In Line";
        String note = String.format(
                "Latest quarter profit grew %.1f%% QoQ vs a trailing average of %.1f%%.",
                latestGrowth, priorAvgGrowth
        );

        return new OutcomeResult(priorTrend.direction(), actualVsExpected, note);
    }

    private static ExpectationResult classifyTrend(List<Double> values) {
        if (values.size() < 3) {
            return new ExpectationResult(INSUFFICIENT_DATA, "Not enough quarterly history to estimate a trend.");
        }

        List<Double> recent = values.size() > 4 ? values.subList(values.size() - 4, values.size()) : values;
        List<Double> growths = new ArrayList<>();
        for (int i = 1; i < recent.size(); i++) {
            double growth = qoqGrowth(recent.get(i - 1), recent.get(i));
            if (ScoreUtils.isValid(growth)) {
                growths.add(growth);
            }
        }

        if (growths.size() < 2) {
            return new ExpectationResult(INSUFFICIENT_DATA, "Not enough quarterly history to estimate a trend.");
        }

        double first = growths.get(0);
        double last = growths.get(growths.size() - 1);
        double avg = growths.stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN);

        String direction;
        if (avg > ACCELERATING_THRESHOLD && last >= first) {
            direction = "Accelerating";
        } else if (avg > DECELERATING_THRESHOLD) {
            direction = "Steady";
        } else {
            direction = "Decelerating";
        }

        String note = String.format(
                "Trailing profit QoQ growth averaging %.1f%% over the last %d quarter(s).", avg, growths.size()
        );

        return new ExpectationResult(direction, note);
    }

    private static double qoqGrowth(double prior, double latest) {
        if (!ScoreUtils.isValid(prior) || !ScoreUtils.isValid(latest) || prior == 0) {
            return Double.NaN;
        }
        return ((latest - prior) / Math.abs(prior)) * 100.0;
    }

    private static double averageQoqGrowth(List<Double> values) {
        if (values.size() < 2) {
            return Double.NaN;
        }
        List<Double> growths = new ArrayList<>();
        for (int i = 1; i < values.size(); i++) {
            double growth = qoqGrowth(values.get(i - 1), values.get(i));
            if (ScoreUtils.isValid(growth)) {
                growths.add(growth);
            }
        }
        return growths.isEmpty() ? Double.NaN : growths.stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN);
    }
}
