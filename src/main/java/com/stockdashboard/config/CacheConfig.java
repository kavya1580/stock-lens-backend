package com.stockdashboard.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

@Configuration
public class CacheConfig {

    @Value("${app.cache.indicators-ttl-seconds}")
    private long indicatorsTtlSeconds;

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager("stockIndicators", "stockSearch");
        manager.setCaffeine(
                Caffeine.newBuilder()
                        .expireAfterWrite(indicatorsTtlSeconds, TimeUnit.SECONDS)
                        .maximumSize(500)
        );

        // Fundamentals move quarterly at most, not intraday, so they don't
        // belong on the same short TTL as price-derived indicators — give
        // them their own cache with a 24h expiry instead.
        manager.registerCustomCache(
                "fundamentals",
                Caffeine.newBuilder()
                        .expireAfterWrite(24, TimeUnit.HOURS)
                        .maximumSize(500)
                        .build()
        );

        manager.registerCustomCache(
            "awardStocks",
            Caffeine.newBuilder()
                .expireAfterWrite(30, TimeUnit.MINUTES)
                .maximumSize(100)
                .build()
        );

        // Board-meeting/results announcements are also a live scrape of the
        // day's feed, so they get the same 30-min TTL as awardStocks above.
        manager.registerCustomCache(
            "resultsUpcoming",
            Caffeine.newBuilder()
                .expireAfterWrite(30, TimeUnit.MINUTES)
                .maximumSize(100)
                .build()
        );

        manager.registerCustomCache(
            "resultsAnnounced",
            Caffeine.newBuilder()
                .expireAfterWrite(30, TimeUnit.MINUTES)
                .maximumSize(100)
                .build()
        );

        // Headlines churn throughout the day, so this needs a much shorter
        // TTL than the fundamentals-style caches above.
        manager.registerCustomCache(
            "news",
            Caffeine.newBuilder()
                .expireAfterWrite(15, TimeUnit.MINUTES)
                .maximumSize(300)
                .build()
        );

        // AI analysis is a paid-in-quota external call per symbol, and the
        // underlying fundamentals/technicals don't meaningfully change
        // intraday, so cache it well beyond the indicators TTL.
        manager.registerCustomCache(
            "geminiAnalysis",
            Caffeine.newBuilder()
                .expireAfterWrite(12, TimeUnit.HOURS)
                .maximumSize(200)
                .build()
        );

        return manager;
    }
}
