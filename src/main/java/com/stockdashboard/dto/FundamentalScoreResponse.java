package com.stockdashboard.dto;

import java.util.List;
import java.util.Map;

public record FundamentalScoreResponse(
        String stockName,
        Double finalScore,
        String rating,
        String dataConfidence,
        List<ScoreBreakdown> scoreBreakdown,
        List<RedFlag> redFlags,
        List<GreenFlag> greenFlags,
        Map<String, Object> derivedMetrics
) {
}
