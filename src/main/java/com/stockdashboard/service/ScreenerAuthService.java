package com.stockdashboard.service;

import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Logs into the user's personal Screener.in account and hands out the resulting
 * session cookies so other services can fetch pages that are gated behind login
 * (e.g. the results calendar). Screener uses a standard Django CSRF-protected
 * login: GET the login page for a csrfmiddlewaretoken + csrftoken cookie, POST
 * credentials + that token, then reuse the session cookie Django hands back.
 *
 * Credentials come from screener-credentials.properties (gitignored, loaded via
 * spring.config.import) - never hardcode them here.
 */
@Service
public class ScreenerAuthService {

    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36";
    private static final String LOGIN_PAGE_URL = "https://www.screener.in/login/?next=/results/latest/";
    private static final String LOGIN_POST_URL = "https://www.screener.in/login/";
    private static final int TIMEOUT_MS = 15_000;

    // Screener doesn't document session lifetime, so this is a conservative in-house cap - any
    // authenticated fetch that comes back redirected to the login page forces a re-login anyway
    // (see getAuthenticated), so this TTL mainly bounds how long a *silently* stale session lingers.
    private static final long SESSION_TTL_MINUTES = 60;

    @Value("${screener.username:}")
    private String username;

    @Value("${screener.password:}")
    private String password;

    private final ScreenerRateLimiter rateLimiter;
    private final ReentrantLock lock = new ReentrantLock();
    private volatile Map<String, String> cachedCookies;
    private volatile Instant cachedAt;

    public ScreenerAuthService(ScreenerRateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
    }

    /** Fetches a page using the cached session, transparently re-logging in once if the session was stale. */
    public Document getAuthenticated(String url) {
        Map<String, String> cookies = getAuthenticatedCookies();
        Connection.Response response = fetch(url, cookies);

        if (isLoginRedirect(response)) {
            cookies = login();
            response = fetch(url, cookies);
            if (isLoginRedirect(response)) {
                throw new IllegalStateException(
                        "Screener.in still redirected to the login page after re-authenticating - " +
                        "check the credentials in screener-credentials.properties.");
            }
        }

        try {
            return response.parse();
        } catch (IOException e) {
            throw new RuntimeException("Failed to parse Screener.in response for " + url, e);
        }
    }

    public Map<String, String> getAuthenticatedCookies() {
        if (isFresh()) {
            return cachedCookies;
        }
        lock.lock();
        try {
            if (isFresh()) {
                return cachedCookies;
            }
            return login();
        } finally {
            lock.unlock();
        }
    }

    private Connection.Response fetch(String url, Map<String, String> cookies) {
        rateLimiter.acquire();
        try {
            return Jsoup.connect(url)
                    .userAgent(USER_AGENT)
                    .cookies(cookies)
                    .timeout(TIMEOUT_MS)
                    .method(Connection.Method.GET)
                    .execute();
        } catch (IOException e) {
            throw new RuntimeException("Failed to fetch " + url + " from Screener.in", e);
        }
    }

    private boolean isLoginRedirect(Connection.Response response) {
        return response.url().toString().contains("/login");
    }

    private boolean isFresh() {
        return cachedCookies != null && cachedAt != null
                && cachedAt.plusSeconds(SESSION_TTL_MINUTES * 60).isAfter(Instant.now());
    }

    private Map<String, String> login() {
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            throw new IllegalStateException(
                    "Screener.in credentials are not configured. Set screener.username and " +
                    "screener.password in screener-credentials.properties at the backend project root.");
        }

        try {
            Connection.Response loginPage = Jsoup.connect(LOGIN_PAGE_URL)
                    .userAgent(USER_AGENT)
                    .timeout(TIMEOUT_MS)
                    .method(Connection.Method.GET)
                    .execute();

            Document loginDoc = loginPage.parse();
            String csrfToken = loginDoc.select("input[name=csrfmiddlewaretoken]").attr("value");
            if (csrfToken.isBlank()) {
                throw new IllegalStateException(
                        "Could not find a CSRF token on the Screener.in login page - its markup may have changed.");
            }

            Connection.Response loginResponse = Jsoup.connect(LOGIN_POST_URL)
                    .userAgent(USER_AGENT)
                    .referrer(LOGIN_PAGE_URL)
                    .cookies(loginPage.cookies())
                    .timeout(TIMEOUT_MS)
                    .data("csrfmiddlewaretoken", csrfToken)
                    .data("username", username)
                    .data("password", password)
                    .data("next", "/results/latest/")
                    .method(Connection.Method.POST)
                    .execute();

            if (isLoginRedirect(loginResponse)) {
                throw new IllegalStateException(
                        "Screener.in rejected the login attempt - check the credentials in " +
                        "screener-credentials.properties.");
            }

            Map<String, String> cookies = new LinkedHashMap<>(loginPage.cookies());
            cookies.putAll(loginResponse.cookies());

            cachedCookies = cookies;
            cachedAt = Instant.now();
            return cachedCookies;
        } catch (IOException e) {
            throw new RuntimeException("Failed to log in to Screener.in", e);
        }
    }
}
