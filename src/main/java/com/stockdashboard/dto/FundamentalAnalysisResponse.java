package com.stockdashboard.dto;

public record FundamentalAnalysisResponse(
        FundamentalsResponse fundamentals,
        FundamentalScoreResponse score
) {
}
