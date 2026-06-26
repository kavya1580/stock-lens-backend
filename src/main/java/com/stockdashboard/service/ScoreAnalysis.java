package com.stockdashboard.service;

import com.stockdashboard.dto.GreenFlag;
import com.stockdashboard.dto.RedFlag;
import com.stockdashboard.dto.ScoreBreakdown;

import java.util.List;
import java.util.Map;

public record ScoreAnalysis(
        List<ScoreBreakdown> scoreBreakdown,
        Double finalScore,
        String rating,
        String dataConfidence,
        List<RedFlag> redFlags,
        List<GreenFlag> greenFlags,
        Map<String, Object> derivedMetrics
) {
}
