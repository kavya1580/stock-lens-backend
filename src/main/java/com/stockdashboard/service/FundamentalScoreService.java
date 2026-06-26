package com.stockdashboard.service;

import com.stockdashboard.dto.FundamentalScoreResponse;
import com.stockdashboard.dto.FundamentalsResponse;
import org.springframework.stereotype.Service;

@Service
public class FundamentalScoreService {

    public FundamentalScoreResponse analyze(FundamentalsResponse fundamentals) {
        ScoreAnalysis analysis = ScoreCalculator.analyze(fundamentals);
        String stockName = fundamentals.companyName() != null && !fundamentals.companyName().isBlank()
                ? fundamentals.companyName()
                : fundamentals.symbol();

        return new FundamentalScoreResponse(
                stockName,
                analysis.finalScore(),
                analysis.rating(),
                analysis.dataConfidence(),
                analysis.scoreBreakdown(),
                analysis.redFlags(),
                analysis.greenFlags(),
                analysis.derivedMetrics()
        );
    }
}
