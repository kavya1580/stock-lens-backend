package com.stockdashboard.service;

import com.stockdashboard.dto.BollingerResult;
import com.stockdashboard.dto.MacdResult;
import com.stockdashboard.dto.OhlcvBar;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Hand-rolled technical indicator math. No external TA library is used so the
 * formulas stay transparent and easy to explain/extend.
 *
 * Every method returns a list the same length as the input, using null for
 * indices where there isn't yet enough history to compute a value (e.g. the
 * first 19 entries of a 20-period SMA).
 */
@Service
public class IndicatorService {

    public List<Double> sma(List<Double> closes, int period) {
        List<Double> result = new ArrayList<>(closes.size());
        for (int i = 0; i < closes.size(); i++) {
            if (i < period - 1) {
                result.add(null);
                continue;
            }
            double sum = 0;
            for (int j = i - period + 1; j <= i; j++) {
                sum += closes.get(j);
            }
            result.add(round(sum / period));
        }
        return result;
    }

    public List<Double> ema(List<Double> closes, int period) {
        List<Double> result = new ArrayList<>(closes.size());
        double multiplier = 2.0 / (period + 1);
        Double prevEma = null;

        for (int i = 0; i < closes.size(); i++) {
            if (i < period - 1) {
                result.add(null);
                continue;
            }
            if (i == period - 1) {
                // seed with simple average of the first `period` closes
                double sum = 0;
                for (int j = 0; j <= i; j++) sum += closes.get(j);
                prevEma = sum / period;
            } else {
                prevEma = (closes.get(i) - prevEma) * multiplier + prevEma;
            }
            result.add(round(prevEma));
        }
        return result;
    }

    /** EMA computed over a series that may itself contain leading nulls (e.g. MACD line). */
    private List<Double> emaOverSeries(List<Double> series, int period) {
        List<Double> result = new ArrayList<>(series.size());
        int firstValid = -1;
        for (int i = 0; i < series.size(); i++) {
            if (series.get(i) != null) { firstValid = i; break; }
        }
        if (firstValid == -1) {
            for (int i = 0; i < series.size(); i++) result.add(null);
            return result;
        }

        double multiplier = 2.0 / (period + 1);
        Double prevEma = null;
        int validCount = 0;

        for (int i = 0; i < series.size(); i++) {
            if (i < firstValid) {
                result.add(null);
                continue;
            }
            validCount++;
            if (validCount < period) {
                result.add(null);
                continue;
            }
            if (validCount == period) {
                double sum = 0;
                for (int j = firstValid; j <= i; j++) sum += series.get(j);
                prevEma = sum / period;
            } else {
                prevEma = (series.get(i) - prevEma) * multiplier + prevEma;
            }
            result.add(round(prevEma));
        }
        return result;
    }

    /** Wilder's RSI - the standard formula used by most charting platforms. */
    public List<Double> rsi(List<Double> closes, int period) {
        List<Double> result = new ArrayList<>(closes.size());
        if (closes.size() < period + 1) {
            for (int i = 0; i < closes.size(); i++) result.add(null);
            return result;
        }

        double avgGain = 0, avgLoss = 0;
        result.add(null); // index 0 has no preceding change

        for (int i = 1; i <= period; i++) {
            double change = closes.get(i) - closes.get(i - 1);
            avgGain += Math.max(change, 0);
            avgLoss += Math.max(-change, 0);
            result.add(null);
        }
        avgGain /= period;
        avgLoss /= period;
        result.set(period, computeRsiValue(avgGain, avgLoss));

        for (int i = period + 1; i < closes.size(); i++) {
            double change = closes.get(i) - closes.get(i - 1);
            double gain = Math.max(change, 0);
            double loss = Math.max(-change, 0);
            avgGain = (avgGain * (period - 1) + gain) / period;
            avgLoss = (avgLoss * (period - 1) + loss) / period;
            result.add(computeRsiValue(avgGain, avgLoss));
        }
        return result;
    }

    private double computeRsiValue(double avgGain, double avgLoss) {
        if (avgLoss == 0) return 100.0;
        double rs = avgGain / avgLoss;
        return round(100 - (100 / (1 + rs)));
    }

    public MacdResult macd(List<Double> closes, int fastPeriod, int slowPeriod, int signalPeriod) {
        List<Double> emaFast = ema(closes, fastPeriod);
        List<Double> emaSlow = ema(closes, slowPeriod);

        List<Double> macdLine = new ArrayList<>(closes.size());
        for (int i = 0; i < closes.size(); i++) {
            if (emaFast.get(i) == null || emaSlow.get(i) == null) {
                macdLine.add(null);
            } else {
                macdLine.add(round(emaFast.get(i) - emaSlow.get(i)));
            }
        }

        List<Double> signalLine = emaOverSeries(macdLine, signalPeriod);

        List<Double> histogram = new ArrayList<>(closes.size());
        for (int i = 0; i < closes.size(); i++) {
            if (macdLine.get(i) == null || signalLine.get(i) == null) {
                histogram.add(null);
            } else {
                histogram.add(round(macdLine.get(i) - signalLine.get(i)));
            }
        }

        return new MacdResult(macdLine, signalLine, histogram);
    }

    public BollingerResult bollinger(List<Double> closes, int period, double numStdDev) {
        List<Double> middle = sma(closes, period);
        List<Double> upper = new ArrayList<>(closes.size());
        List<Double> lower = new ArrayList<>(closes.size());

        for (int i = 0; i < closes.size(); i++) {
            if (middle.get(i) == null) {
                upper.add(null);
                lower.add(null);
                continue;
            }
            double mean = middle.get(i);
            double sumSquares = 0;
            for (int j = i - period + 1; j <= i; j++) {
                double diff = closes.get(j) - mean;
                sumSquares += diff * diff;
            }
            double stdDev = Math.sqrt(sumSquares / period);
            upper.add(round(mean + numStdDev * stdDev));
            lower.add(round(mean - numStdDev * stdDev));
        }
        return new BollingerResult(upper, middle, lower);
    }

    public List<Double> avgVolume(List<OhlcvBar> bars, int period) {
        List<Double> volumes = new ArrayList<>(bars.size());
        for (OhlcvBar bar : bars) {
            volumes.add(bar.volume() != null ? bar.volume().doubleValue() : 0.0);
        }
        return sma(volumes, period);
    }

    private double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
