package com.stockdashboard.dto;

public record AnnouncedResultResponse(
        String companyName,
        String symbol,
        String marketCap,
        Double fundamentalScore,
        String rating,
        String resultDate,
        String latestQuarterSales,
        String latestQuarterNetProfit,
        Double qoqProfitGrowthPercent,
        String priorTrendDirection,
        String actualVsExpected,
        String note,
        String announcementHeadline,
        String announcementDate,
        String sourceUrl
) {
}
