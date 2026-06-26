package com.stockdashboard.dto;

import java.util.Map;

public record ScoreBreakdown(
        String category,
        Double score,
        Double weight,
        String explanation,
        Map<String, Object> contributingMetrics
) {
}
