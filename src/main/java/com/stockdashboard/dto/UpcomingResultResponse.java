package com.stockdashboard.dto;

public record UpcomingResultResponse(
        String companyName,
        String symbol,
        String marketCap,
        Double fundamentalScore,
        String rating,
        String boardMeetingDate,
        String expectedDirection,
        String expectedNote,
        String announcementHeadline,
        String announcementDate,
        String sourceUrl
) {
}
