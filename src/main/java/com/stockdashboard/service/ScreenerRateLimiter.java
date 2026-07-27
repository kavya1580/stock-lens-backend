package com.stockdashboard.service;

import org.springframework.stereotype.Component;

/**
 * Enforces a minimum spacing between outbound requests to Screener.in, shared across every
 * caller (BseAwardStockService, ScreenerResultsCalendarService, ScreenerAuthService's own login)
 * regardless of how many threads call in concurrently. Screener's limiter is keyed on request
 * rate over time - not on concurrent connection count - so a small thread pool alone doesn't
 * stop a 50-row page x up to 3 Screener calls per row from firing enough requests per minute to
 * trip it. This is enforced in ScreenerAuthService.fetch(), the one chokepoint every outbound
 * call (page fetch, standalone fallback, peer-comparison API) already passes through.
 */
@Component
public class ScreenerRateLimiter {

    private static final long MIN_INTERVAL_MS = 1200;

    private long nextSlotMs = 0;

    public synchronized void acquire() {
        long now = System.currentTimeMillis();
        long waitMs = nextSlotMs - now;
        nextSlotMs = Math.max(now, nextSlotMs) + MIN_INTERVAL_MS;
        if (waitMs > 0) {
            sleep(waitMs);
        }
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while waiting for the Screener.in rate limiter", e);
        }
    }
}
