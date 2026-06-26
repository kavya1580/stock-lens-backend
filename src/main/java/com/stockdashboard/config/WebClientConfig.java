package com.stockdashboard.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * NEW FILE — YahooFinanceService constructor-injects a bean named
 * "yahooWebClient" that wasn't among the files you shared. A default
 * browser-like User-Agent is set since Yahoo's chart endpoint occasionally
 * 429s requests that look like bare server-to-server calls.
 */
@Configuration
public class WebClientConfig {

    @Bean
    public WebClient yahooWebClient() {
        return WebClient.builder()
                .defaultHeader(HttpHeaders.USER_AGENT,
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36")
                .build();
    }
}
