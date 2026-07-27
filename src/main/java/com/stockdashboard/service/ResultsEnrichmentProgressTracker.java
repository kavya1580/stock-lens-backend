package com.stockdashboard.service;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tracks per-company enrichment progress for the announced-results feed so the
 * frontend can poll a lightweight endpoint and show "N of M enriched" while a
 * fetchAnnouncedResults() call is in flight - that call itself blocks until every
 * row on the page is enriched, so it can't report progress on its own response.
 *
 * Deliberately a single global counter rather than per-request/per-session state:
 * this is a personal, single-user app with one Screener account, so there's only
 * ever meant to be one fetch in flight at a time. The generation counter below
 * exists only to stop a slow, superseded request's completions from corrupting a
 * newer request's counts - not to support real concurrent users.
 */
@Component
public class ResultsEnrichmentProgressTracker {

    private final AtomicInteger generation = new AtomicInteger(0);
    private final AtomicInteger total = new AtomicInteger(0);
    private final AtomicInteger completed = new AtomicInteger(0);

    public record Snapshot(int completed, int total) {
    }

    /** Call once, right before dispatching a page's worth of enrichment futures. Returns a token for increment(). */
    public int start(int totalCount) {
        int currentGeneration = generation.incrementAndGet();
        total.set(totalCount);
        completed.set(0);
        return currentGeneration;
    }

    /** Call once per row as its enrichment future completes (success, failure, or timeout alike). */
    public void increment(int generationToken) {
        if (generationToken == generation.get()) {
            completed.incrementAndGet();
        }
    }

    public Snapshot snapshot() {
        return new Snapshot(completed.get(), total.get());
    }
}
