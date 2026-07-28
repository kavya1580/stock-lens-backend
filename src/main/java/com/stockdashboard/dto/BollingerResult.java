package com.stockdashboard.dto;

import java.util.List;

public record BollingerResult(
        List<Double> upper,
        List<Double> middle,
        List<Double> lower
) {
}
