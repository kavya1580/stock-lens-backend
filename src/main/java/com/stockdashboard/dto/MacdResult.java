package com.stockdashboard.dto;

import java.util.List;

public record MacdResult(
        List<Double> macdLine,
        List<Double> signalLine,
        List<Double> histogram
) {
}
