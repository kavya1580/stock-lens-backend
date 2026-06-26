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

        return manager;
    }
}
