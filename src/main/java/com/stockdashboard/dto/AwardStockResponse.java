package com.stockdashboard.dto;

public record AwardStockResponse(
        String companyName,
        String symbol,
        String orderFromWho,
        String orderAmount,
        String marketCap,
        Double fundamentalScore,
        String rating,
        String announcementHeadline,
        String announcementDate,
        String sourceUrl
) {
}