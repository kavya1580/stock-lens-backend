package com.stockdashboard.dto;

/** Structured AI-generated analysis for the AI Analysis tab, produced by GeminiService. */
public record AiAnalysisResponse(
        String verdict,
        String overallOpinion,
        String businessQuality,
        String risks,
        String competitiveAdvantage,
        String earningsSummary
) {
}
