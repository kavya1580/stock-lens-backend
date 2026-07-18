package com.stockdashboard.service;

import com.stockdashboard.dto.NewsItem;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;
import org.jsoup.select.Elements;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Pulls recent headlines for a company from Google News' public RSS search
 * feed. No API key required. Best-effort: any fetch/parse failure returns an
 * empty list rather than surfacing an error, since a missing news tab isn't
 * worth a 500 to the frontend.
 */
@Service
public class NewsService {

    private static final String RSS_URL =
            "https://news.google.com/rss/search?q=%s&hl=en-IN&gl=IN&ceid=IN:en";
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36";
    private static final int REQUEST_TIMEOUT_MS = 10_000;
    private static final int MAX_ITEMS = 20;

    @Cacheable(value = "news", key = "#companyName")
    public List<NewsItem> fetchNews(String companyName) {
        try {
            String query = URLEncoder.encode(companyName + " stock", StandardCharsets.UTF_8);
            String url = String.format(RSS_URL, query);

            Document doc = Jsoup.connect(url)
                    .userAgent(USER_AGENT)
                    .timeout(REQUEST_TIMEOUT_MS)
                    .parser(Parser.xmlParser())
                    .get();

            Elements items = doc.select("item");
            List<NewsItem> news = new ArrayList<>();

            for (Element item : items) {
                if (news.size() >= MAX_ITEMS) {
                    break;
                }

                String link = item.selectFirst("link") != null ? item.selectFirst("link").text() : "";
                String publishedAt = item.selectFirst("pubDate") != null ? item.selectFirst("pubDate").text() : "";
                Element sourceEl = item.selectFirst("source");
                String source = sourceEl != null ? sourceEl.text() : "Google News";

                Element titleEl = item.selectFirst("title");
                String title = titleEl != null ? titleEl.text() : "";
                String suffix = " - " + source;
                if (title.endsWith(suffix)) {
                    title = title.substring(0, title.length() - suffix.length());
                }

                if (title.isBlank() || link.isBlank()) {
                    continue;
                }

                news.add(new NewsItem(title, link, source, publishedAt));
            }

            return news;
        } catch (Exception e) {
            System.out.println("Failed to fetch news for " + companyName + ": " + e.getMessage());
            return List.of();
        }
    }
}
