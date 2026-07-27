# Architecture Reference

Standing reference for this codebase so future work can read this file
instead of re-reading source across `controller/`, `service/`, `dto/`.
Endpoints and run instructions live in `README.md` — not duplicated here.
For a feature-by-feature "how it actually works internally" narrative
(scraping mechanics, indicator formulas, prompt construction, etc.), see
`FUNCTIONALITY.md`.

Server runs on **port 8082** (`application.properties`'s `server.port`) —
README's `localhost:8080` is stale.

## Package Map

**`controller/`**
- `StockController` — the only `@RestController`, base path `/api/stocks`. Wires 8 services into **11 endpoints**:
  - `GET /search` → `StockSearchService`
  - `GET /{symbol}/indicators` → `StockAnalysisService` (technical/OHLCV)
  - `GET /{symbol}/fundamentals` → `ScreenerScraperService` (raw scrape only)
  - `GET /{symbol}/fundamentals/analysis` → scrape + `FundamentalScoreService` combined — this is what the frontend's main dashboard actually consumes
  - `GET /{symbol}/score` → scrape + score, score only
  - `GET /{symbol}/overview` → fundamentals + indicator *summary* (not full series — that's what `/indicators` is for). Not currently called by the frontend.
  - `GET /awards` → `BseAwardStockService` (Order Wins feed)
  - `GET /results/upcoming` / `GET /results/announced` → `ScreenerResultsCalendarService`
  - `GET /{symbol}/ai-analysis` → `GeminiService.analyze()`
  - `POST /{symbol}/ai-chat` → `GeminiService.chat()`
  - `GET /{symbol}/news` → `NewsService` (re-fetches cached fundamentals first, just to resolve the company name for the news query)

**`service/`**
- `ScreenerAuthService` — **new since this doc was last updated.** Logs into the user's personal Screener.in account (standard Django CSRF login: GET `/login/` for a `csrfmiddlewaretoken` + cookie, POST credentials, cache the resulting session cookies) and hands out `getAuthenticated(url)` for any other service that needs a page gated behind login. Session cookies are cached in-memory (`SESSION_TTL_MINUTES=60`) and transparently refreshed on the first request that gets redirected back to the login page. Credentials come from a gitignored `screener-credentials.properties` at the project root (`spring.config.import=optional:file:./screener-credentials.properties`), never hardcoded. Both `ScreenerScraperService` and `ScreenerResultsCalendarService` now fetch exclusively through this authenticated session — there is no anonymous JSoup fetch left anywhere in the Screener scraping path.
- `ScreenerScraperService` — scraper for `screener.in/company/<SYMBOL>`, fetched via `ScreenerAuthService.getAuthenticated` (not a bare JSoup connection). Builds `FundamentalsResponse`. Matches table rows by **label text**, not stable CSS selectors/IDs — fragile to Screener markup changes (see Gotchas). Wraps every fetch in `fetchWithRetry`, which retries on any `IOException` (HTTP 429 *or* a stalled connection that times out with no response — both are the same underlying rate-limit, just manifesting differently) with linear backoff, short-circuiting immediately on a genuine 404 instead of retrying (see Gotchas).
- `FundamentalScoreService` — thin orchestration wrapper: calls `ScoreCalculator.analyze()`, adapts `ScoreAnalysis` into `FundamentalScoreResponse`.
- `ScoreCalculator` — the scoring engine. 10 weighted categories → `finalScore`, plus red/green flags. See "Scoring Model" below.
- `ScoreUtils` — stateless helper library: `parseDouble`/`parseSeriesValues`/`isValid` (string→number parsing with graceful failure), `interpolate(value, thresholds[][])` (piecewise-linear scoring), `linearSlope(series)`, `weightedScore(scores, weights)` (reweights over only the sub-metrics actually present — this is *why* missing data degrades gracefully instead of erroring), and one `scoreXxx`/`computeXxx` function per sub-metric.
- `DerivedMetricsCalculator` — computes cross-field metrics that don't live directly on a Screener row: `relativePE`, `peg`, `promoterSlope`, `institutionalSlope` (linear slope of the FII+DII sum series — the authoritative institutional-trend number, see Gotchas), plus the human-readable `derivedMetrics` trend labels shown in the API response. Note `linearSlope` is a two-point `(last-first)/(n-1)` rate, not an OLS regression, despite the name.
- `StockAnalysisService` / `YahooFinanceService` / `IndicatorService` — technical side: OHLCV fetch from Yahoo Finance's public chart API, indicator computation (SMA/EMA/RSI-Wilder/MACD/Bollinger, all hand-rolled, no TA library). Independent of the Screener/fundamentals path.
- `StockSearchService` — ticker search/autocomplete, backed by `data/nse_symbols.csv`, refreshed nightly from NSE's own equity-list CSV via a `@Scheduled` cron job with a &lt;500-row anti-corruption guard.
- `BseAwardStockService` — BSE "Award of Order" announcements (Order Wins feed), enriched in parallel per-row with fundamentals/score where a stock symbol can be resolved from the free-text company name. Unrelated to fundamentals scoring itself.
- `ScreenerResultsCalendarService` + `ResultExpectationCalculator` — **replaces `BseResultsCalendarService` (deleted).** No longer mines BSE's generic Board Meeting announcements feed by regex; instead reads the user's own authenticated Screener.in account's `/results/latest/` page directly via `ScreenerAuthService`, one day at a time (`result_update_date__day/month/year` query params — confirmed live to actually filter by day). Verified live that Screener has **no forward-looking results feed at all** (`/results/calendar/` doesn't exist), so `fetchUpcomingResults` always returns an empty page rather than fabricating data. Market-cap filtering (`MIN_MARKET_CAP_CR=200`) is done client-side in Java on the already-parsed per-row value, because adding Screener's own `mcap_min`/`mcap_max` params silently breaks the day filter (see Gotchas). `ResultExpectationCalculator` is unchanged and still an explicitly self-referential heuristic — it compares a company's trailing quarterly profit trend against itself, it is **not** analyst-consensus beat/miss, despite the "Beat Trend"/"Below Trend" naming. See `FUNCTIONALITY.md` for the full mechanics.
- `GeminiService` — **new since this doc was last updated.** Wraps Google Gemini's `generateContent` REST API (model `gemini-2.5-flash`, hand-built JSON, no SDK) for both `/ai-analysis` (structured single-shot, JSON-schema-constrained output, cached 12h) and `/ai-chat` (free-text, multi-turn, stateless — full conversation history must be resent by the client every call, not cached). Both feed the *entire* `FundamentalsResponse` + `LatestSnapshot` JSON to Gemini as context — no curation.
- `NewsService` — **new since this doc was last updated.** Pulls Google News' public RSS feed (no API key) for `"<Company Name> stock"`, parsed as XML via JSoup. Fails silently to an empty list on any error, by design (unlike `GeminiService`, which has no fallback and surfaces failures as 500s).
- `ScoreAnalysis` — internal record carrying `ScoreCalculator`'s full result (breakdowns, finalScore, rating, dataConfidence, flags, derivedMetrics) before it's adapted into the public `FundamentalScoreResponse` DTO.

**`dto/`** — API response records. Notable ones: `FundamentalsResponse` (raw scrape, ~70 fields, positional record — see Gotchas), `FundamentalScoreResponse`/`ScoreBreakdown`/`RedFlag`/`GreenFlag` (scoring output), `FundamentalAnalysisResponse` (fundamentals + score combined), `StockOverviewResponse`, `StockIndicatorResponse`, `LatestSnapshot`, `UpcomingResultResponse`/`AnnouncedResultResponse` (results calendar), `AiAnalysisResponse`/`AiChatMessage`/`AiChatRequest`/`AiChatResponse` (Gemini), `NewsItem`.

**`config/`** — `CorsConfig` (allows only `localhost:3000`/`localhost:5173` — no prod origin configured yet), `WebClientConfig` (two near-identical browser-UA-spoofing `WebClient` beans for Yahoo/NSE), `CacheConfig` (7 named Caffeine caches — see table below), `OpenApiConfig` (Swagger UI at `/swagger-ui.html`; its description string is stale, still says "Technical indicator dashboard" only). Standard Spring Boot wiring, nothing scoring-specific.

**`exception/`** — `StockNotFoundException` + `GlobalExceptionHandler` (`@ControllerAdvice`). Note `GeminiService`/BSE-service failures other than symbol-not-found are *not* individually caught anywhere and fall through to the generic 500 handler.

### Cache table (`CacheConfig`)

| Cache | TTL | Used by |
|---|---|---|
| `fundamentals` | 24h | `ScreenerScraperService` |
| `stockIndicators` | 30m | `StockAnalysisService` |
| `awardStocks` | 30m | `BseAwardStockService` |
| `resultsUpcoming` | 30m | `ScreenerResultsCalendarService` (always caches the same empty page — see Gotchas) |
| `news` | 15m | `NewsService` |
| `geminiAnalysis` | 12h | `GeminiService.analyze` (not `chat`, which is never cached) |
| `stockSearch` | 30m | registered but **unused** — `StockSearchService.search()` has no `@Cacheable` |

Note: `resultsAnnounced` was **removed entirely** (no longer registered in `CacheConfig`, no `@Cacheable` on `fetchAnnouncedResults`) — see Gotchas for why.

## Scoring Model (`ScoreCalculator`)

`analyze()` computes 10 category breakdowns, each independently reweighted
via `ScoreUtils.weightedScore` over whatever sub-metrics are present for that
stock, then combines categories by their **fixed category weight** (below)
into `finalScore` — again only over categories that actually produced a
score. `dataConfidence` reports how many of the 10 categories computed
(`N/10`).

| Category | Weight | Sub-metrics (weight within category) |
|---|---|---|
| Profitability | 16 | non-financial: roe .30, roce .30, roa .15, opm .15, npm .10 — financial-sector: roe .383, roce .383, roa .233 (opm/npm skipped for banks/NBFC/insurance) |
| Growth | 16 | salesGrowth5Y .25, salesGrowthTTM .20, profitGrowth5Y .20, profitGrowthTTM .15, salesGrowthTrend .10, epsQuarterlyTrend .10 |
| Financial Health | 13 | non-financial: debtToEquity .40, interestCoverage .30, currentRatio .15, leverageTrend .15 — financial-sector: interestCoverage .50, currentRatio .35, leverageTrend .15 (D/E skipped for banks/NBFC) |
| Cash Flow Quality | 13 | cfoToOperatingProfitLatest .35, cfoToOperatingProfitSeries .20, freeCashFlowSeries .20, freeCashFlowLatest .15, operatingCashFlowSeries .10 |
| Earnings Quality | 10 | taxRateStability .35, otherIncomeDependency .30, profitSalesDivergence .35 |
| Valuation | 10 | PEG-present: peg .35, relativePE .25, pbRatio .20, evEbitda .10, dividendYield .05, dividendPayout .05 — PEG-absent: relativePE .50, pbRatio .30, evEbitda .10, dividendYield .05, dividendPayout .05 |
| Momentum | 8 | roceTrend .25, roeTrend .20, salesGrowthAcceleration .20, profitGrowthAcceleration .15, marginTrend .20 |
| Risk (inverse — higher = safer) | 8 | leverageRisk .30, ownershipRisk .25, earningsQualityRisk .20, workingCapitalRisk .15, liquidityRisk .10 |
| Ownership | 4 | promoterTrend .30, institutionalTrend .30, promoterLevel .15, promoterPledge .15, publicHolding .10 |
| Efficiency | 2 | assetTurnover .30, inventoryTurnover .25, cashConversionCycle .25, workingCapitalDays .20 |

Weights sum to 100 across categories, and to 1.0 within each category variant.

`dividendPayout` (Valuation) and `promoterPledge` (Ownership) were added to
track the `long-term-investing-analysis` skill's rubric (see below); both
are best-effort — Screener frequently omits pledge data entirely, in which
case the sub-metric is simply absent and `weightedScore` reweights over the
rest.

### Red Flags (`buildRedFlags`)
- Low CFO/OP (`< 60%`)
- Low Interest Coverage (`< 1.5x`)
- Weak Liquidity (current ratio `< 1.0`)
- High Other Income Dependency (`> 20%` of operating profit)
- Declining Promoter Holding (slope `< -0.1`)
- Negative Free Cash Flow (negative in majority of recent years)
- Negative Reserves / Net Worth (`reserves < 0`)
- Unsustainable Dividend Payout (`> 100%` of profit in 2+ recent years)
- Promoter Shares Pledged (`> 10%`)
- Screener Flag — one entry per item in Screener's own scraped `cons[]`, verbatim, labeled as site-generated

### Green Flags (`buildGreenFlags`)
- Strong CFO/OP (`>= 90%`)
- Consistent Positive FCF (positive in `>= 60%` of recent years)
- Improving ROCE (latest `>=` 10-year average `+ 10`)
- Stable / Rising Promoter Holding (slope `>= 0`)
- Institutional Accumulation (`institutionalSlope > 0.2`, sourced from `DerivedMetricsCalculator`)
- Strong Balance Sheet (D/E `<= 0.5` and interest coverage `> 6.0`)

### Rating bands (`mapRating`)
`>=90` Exceptional · `>=80` Excellent · `>=70` Strong · `>=60` Average · `>=50` Weak · else Risky.

## Relevant Claude Skills

- `long-term-investing-analysis` (`~/.claude/skills/long-term-investing-analysis`) — the rubric this endpoint's scoring model is meant to track. Its `references/scoring-rubric.md` is the source for the dividend-payout and promoter-pledge sub-metrics above, and for the red-flag list. This repo's model differs in structure (10 weighted categories reweighted per-stock vs. the skill's fixed 7-category/100-pt sum) but should stay directionally aligned on *what* gets penalized/rewarded.
- `swing-trade-analysis` — technical/price-based, covers the `/indicators` and `/overview` technical-summary side of this repo, not `/fundamentals` or the scoring engine above.

## Known Gotchas

- **A live Gemini API key is committed in plaintext** in `application.properties` (`app.gemini.api-key`), directly beneath a comment instructing it be set via the `GEMINI_API_KEY` environment variable instead — that advice was not followed. Treat as a security remediation item: rotate the key and move it out of the tracked file.
- **`Untitled-1.html`** (`service/Untitled-1.html`) and **`PageSource.html`** (`com/stockdashboard/PageSource.html`) are saved Screener.in pages sitting under `src/main/java` (not `src/test/resources`). They are dev fixtures used to eyeball scraper output against real HTML — not wired into any test or build step, and safe to ignore (or relocate) but not delete without checking they aren't referenced elsewhere first. `Untitled-1.html` is an unrelated NBFC ("Indus Finance Ltd") page, not Lupin. **`JsoupProbe.java`** at the project root (outside `src/main/java`, won't compile into the app) is the same kind of standalone scraper-debugging fixture.
- **`ScreenerScraperService` matches by label text and document order**, not stable selectors. `extractLatestRowValue`/`extractRowSeries` scan for a row whose first cell matches one of the given label strings; `extractRowSeries`'s quarterly-vs-yearly disambiguation for some tables depends on which table appears first in the page, not an explicit id/class. A Screener markup reshuffle can silently break extraction (falls back to `"—"`/absent rather than throwing, per the `isValid`/`weightedScore` graceful-degradation pattern — but that also means breakage is silent unless you check `dataConfidence`). It also still has leftover debug `System.out.println` calls (symbol/fallback logging, dumping the full top-ratios map). The real fetch timeout is `ScreenerAuthService.TIMEOUT_MS=15_000` (used consistently for every Screener request, login included) — there is no separate/unused timeout constant in `ScreenerScraperService` anymore.
- **Screener rate-limits per-company fetches aggressively under concurrent load** — both an explicit HTTP 429 and, just as often, a connection that simply never responds (`SocketTimeoutException`) well before any real "not found" case would occur. `ScreenerScraperService.fetchWithRetry` retries on *any* `IOException` cause with linear backoff (`RETRY_BACKOFF_MS=1000` × attempt, `MAX_ATTEMPTS=6` — up to ~15s added latency in the worst case), special-casing a genuine HTTP 404 to fail fast as `StockNotFoundException` instead of burning retries on it. Previously only `HttpStatusException(429)` was retried, so a `SocketTimeoutException` fell through to an immediate rethrow with zero retries — this under-reported real failures as if Screener had confirmed "no such stock."
- **`ScreenerResultsCalendarService` uses two separate executor pools sized for different failure modes**: `DAY_FETCH_EXECUTOR` (sized `max(4, min(12, cores*2))`) fans out the 1-2 lookback-day page fetches, while `ENRICHMENT_EXECUTOR` is a deliberately small fixed pool of **3** for per-company `fetchFundamentals` calls — empirically, Screener throttles/stalls most concurrent company-page requests past a handful regardless of session. Each row's enrichment is wrapped in `withTimeout` (`ENRICHMENT_TIMEOUT_SECONDS=120`, via `orTimeout`+`handle` so timeout vs. thrown-exception are logged distinctly) and falls back to a partial "Insufficient Data" row rather than failing the whole page.
- **Screener's own `mcap_min`/`mcap_max` query params silently break `/results/latest/`'s day filter** — verified live that adding them makes Screener ignore the requested day and return a fixed default listing instead (including sub-threshold companies), so `MIN_MARKET_CAP_CR=200` filtering is done in Java on the market-cap value already parsed per row, not passed to Screener as a query param.
- **`resultsAnnounced` was deliberately made *not* `@Cacheable`** (unlike everything else in this table) — the previous whole-page cache kept serving a page full of "Insufficient Data" fallback rows for the full 30-min TTL even after Screener's rate limit had cleared. Reliance shifted to the pre-existing per-symbol `fundamentals` cache instead: Spring doesn't cache thrown exceptions, so a request that previously failed naturally gets retried (not just replayed from a stale cached failure) on the next call, while successes still short-circuit the scrape.
- **`FundamentalsResponse` is a positional Java record.** Its field order must exactly match the constructor call in `ScreenerScraperService.fetchFundamentals`. Adding/reordering a field requires updating both in lockstep — there's no named-argument safety net.
- **`promoterPledge` defaults to `"0%"`, not `"—"`, when Screener has no pledge data** — this silently conflates "unknown" with "confirmed zero," which could understate risk in both the score and the pledge red flag.
- **Some metrics are scored in more than one category**, so their real influence on `finalScore` exceeds their nominal category weight: `debtToEquity`/`interestCoverage` appear in both Financial Health and Risk; `promoterHolding` appears in both Ownership (`promoterLevel`) and Risk (`ownershipRisk`); `otherIncomeDependency` appears in both Earnings Quality and Risk; `cashConversionCycle` appears in both Efficiency and Risk. This is intentional (Risk is a deliberately-correlated downside lens) but worth remembering when explaining why a single weak metric moved the final score more than its category weight suggests.
- **BSE is no longer used for the results calendar at all** — only `BseAwardStockService` (Order Wins) still hits BSE's announcements API; `BseResultsCalendarService` was deleted outright (fully replaced by `ScreenerResultsCalendarService`, not run alongside it). The old "duplicate code between the two BSE services" gotcha no longer applies since there's only one BSE-backed service left.
- **Logging into Screener.in with real personal credentials** — `screener-credentials.properties` (gitignored, project root, loaded via `spring.config.import=optional:file:...`) holds `screener.username`/`screener.password`. This is personal-account automation, not a public API integration: keep request volume low and never commit that file. A missing/blank credential fails fast with a message pointing at the file; a rejected login or a still-login-redirected fetch after one retry throws `IllegalStateException` rather than silently returning empty data.
- **AI chat is fully stateless server-side** — every `/ai-chat` call resends the entire fundamentals+technicals context as Gemini's system instruction, and the client must replay the full conversation history each turn. There's no session id, no server-side chat log.
- **No `mvn`/`mvnw` binary was available** in the shell environment this doc was written in — scoring-logic changes here were verified by hand-tracing against a captured LUPIN `/fundamentals` response, not by a real build. Run `mvn spring-boot:run` (or build via IntelliJ) after any change here before trusting it compiles.
