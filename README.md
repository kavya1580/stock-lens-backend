# Stock Dashboard Backend (complete, merged)

Spring Boot backend combining fundamental analysis (Screener.in scrape) with
technical analysis (Yahoo Finance OHLCV + hand-rolled indicators).

## Endpoints

```
GET /api/stocks/search?q=TCS                 -> symbol/name search
GET /api/stocks/{symbol}/fundamentals         -> Screener.in metrics
GET /api/stocks/{symbol}/indicators           -> full OHLCV + SMA/EMA/RSI/MACD/Bollinger history
GET /api/stocks/{symbol}/overview             -> fundamentals + latest technical snapshot only
```

## Run it

```
mvn spring-boot:run
```

Starts on `http://localhost:8080`.

```
curl http://localhost:8080/api/stocks/TCS/overview
```

## File provenance — read before trusting this over your own copies

This was assembled from three rounds of files you shared plus the fundamentals
work from earlier in our conversation. Not everything could come from a
verified source:

**Yours, unchanged** — `IndicatorService.java`, `StockAnalysisService.java`,
`StockSearchService.java`, `YahooFinanceService.java`, `StockNotFoundException.java`.

**Yours, modified** — `StockController.java` (added the `/fundamentals` and
`/overview` endpoints), `CacheConfig.java` (added a separate 24h `fundamentals`
cache instead of sharing the indicators TTL).

**Mine, from earlier in this conversation** — `ScreenerScraperService.java`,
`FundamentalsResponse.java`, `StockOverviewResponse.java`.

**RECONSTRUCTED — verify against your real files if you have them**, since you
never shared the originals: `OhlcvBar`, `MacdResult`, `BollingerResult`,
`StockIndicatorResponse`, `LatestSnapshot`, `StockSearchResult`. Every field
was inferred from how it's constructed/accessed in the code you did share, so
confidence is high, but two specific spots are genuine guesses rather than
certainties:
- `LatestSnapshot`'s 6th field — `buildLatestSnapshot()` always passes a
  literal `null` there. I've named it `ema20` as the most plausible intent,
  but it's currently dead weight either way (always null) until something
  populates it.
- `StockSearchResult`'s third field — I went with `series` (matching NSE's
  own published symbol-list format), but your actual CSV/record may use
  something else.

**Brand new, not previously discussed** — `StockDashboardApplication.java`
(main class), `WebClientConfig.java` (the `yahooWebClient` bean
`YahooFinanceService` expects), `CorsConfig.java`, `GlobalExceptionHandler.java`,
`pom.xml`, `application.properties`. If you already have working versions of
any of these in your real project, keep yours and skip mine — these are
filling gaps, not replacing anything you confirmed exists.

**Placeholder, replace this one** — `nse_symbols.csv` has 9 sample rows so the
app boots and `/search` returns something to test with. Swap in your actual
full NSE symbol list (`StockSearchService` expects the same 3-column,
header-then-rows CSV format).

## If it doesn't compile

Almost certainly one of the reconstructed DTOs doesn't match your real one
field-for-field. Paste the compiler error and the real DTO and I'll fix the
mismatch immediately rather than you needing to debug a guess.
