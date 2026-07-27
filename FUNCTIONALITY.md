# Backend Functionality — Behind the Scenes

This doc walks through every feature the backend exposes and explains the
actual mechanics behind it: where the data comes from, how it's parsed, what
it computes, and where it's fragile. Pairs with `ARCHITECTURE.md` (package
map, scoring-model reference table) — this file is the narrative "how it
really works" companion. All 11 endpoints live on `StockController`
(`/api/stocks/**`); symbols are always normalized to `symbol.trim().toUpperCase()`
before touching any service.

## 1. Stock Search — `GET /api/stocks/search?q=`

**What it does:** autocomplete-style symbol/name search, limit hardcoded to 10 results.

**Behind the scenes:**
- Backed by an in-memory list loaded from `data/nse_symbols.csv` (NOT the
  10-row placeholder shipped at `src/main/resources/nse_symbols.csv`, which
  only exists to seed the real file on first boot via `seedFromClasspath`).
- On startup, `StockSearchService.init()` (`@PostConstruct`) loads whatever's
  on disk, then immediately kicks off an async refresh so a fresh checkout
  doesn't have to wait until 2am for real data.
- A `@Scheduled` job (default cron `0 0 2 * * *`, Asia/Kolkata) re-downloads
  NSE's own official equity list — `https://nsearchives.nseindia.com/content/equities/EQUITY_L.csv`
  — via a `WebClient` carrying a browser `User-Agent` (NSE blocks bare
  server-side requests with a 403 otherwise).
- **Anti-corruption guard**: if the freshly parsed CSV has fewer than 500
  rows, the refresh is treated as bad/blocked data (NSE returning an HTML
  block page, a truncated download, etc.) and the old in-memory list is kept
  instead of being overwritten. On success, the new file is written
  atomically (`.tmp` + `ATOMIC_MOVE`) before swapping the in-memory list.
- **Matching**: query uppercased; two-pass bucketing — symbols that
  `startsWith(query)` first, then names that `contains(query)`, concatenated
  and truncated to the limit. No fuzzy/typo tolerance.
- Not actually cached — a `stockSearch` Caffeine cache is registered in
  `CacheConfig` but `search()` has no `@Cacheable` annotation on it.

## 2. Fundamentals Scraping — `GET /api/stocks/{symbol}/fundamentals`

**What it does:** returns ~70 raw fundamental data points (ratios, growth
series, shareholding, pros/cons) scraped live from Screener.in — no
third-party financial data API is used anywhere in this project.

**Behind the scenes:**
- Fetches `https://www.screener.in/company/{SYMBOL}/consolidated/` through
  `ScreenerAuthService.getAuthenticated(url)` — **not** a bare anonymous
  JSoup connection. Every Screener request in this project (fundamentals,
  peer P/E lookups, and the results calendar) now rides the same logged-in
  session, cookies and all, rather than fetching public pages anonymously.
  If the consolidated page's Market Cap value looks empty/blank, it silently
  refetches from the standalone URL (`.../company/{SYMBOL}/`) instead —
  some companies only publish standalone financials.
- **Login is handled entirely by `ScreenerAuthService`**, a small standalone
  service: `GET /login/?next=/results/latest/` to grab a
  `csrfmiddlewaretoken` + `csrftoken` cookie (standard Django CSRF login),
  then `POST /login/` with the username/password/token/cookie. The
  resulting session cookies are cached in-memory for up to 60 minutes; any
  authenticated fetch that comes back redirected to `/login` triggers one
  transparent re-login before giving up. Credentials live in a gitignored
  `screener-credentials.properties` at the project root (loaded via
  `spring.config.import=optional:file:./screener-credentials.properties`),
  never in a tracked file.
- **Retries absorb Screener's rate-limiting, not just 404s**:
  `fetchWithRetry` retries on *any* `IOException` — an explicit HTTP 429
  *and*, just as often, a connection that hangs and times out with no
  response at all (`SocketTimeoutException`) — with linear backoff
  (`RETRY_BACKOFF_MS=1000` × attempt number, up to `MAX_ATTEMPTS=6`, so up
  to ~15s of added latency in the worst case). A genuine HTTP 404 is
  special-cased to fail immediately as `StockNotFoundException` rather than
  burning retries on a stock that really doesn't exist. This was a real bug
  fix: the retry logic originally only matched `HttpStatusException`, so a
  `SocketTimeoutException` — the *more* common failure mode under
  concurrent load — fell through to an immediate rethrow with zero retries.
- **Extraction is entirely label-driven, not ID/selector-driven**: every
  table row is scanned for a first cell whose text matches one of a set of
  candidate label strings (case-insensitive, whitespace-normalized). This
  means a Screener markup or wording change can silently break a field —
  extractors never throw, they just fall back to `"—"` or an empty
  map/list, so breakage is invisible unless you check `dataConfidence` on
  the scoring response.
- Quarterly vs. yearly series disambiguation for some tables depends on
  *which table appears first in the page* — there's no explicit id telling
  the parser which is which.
- A handful of ratios Screener only shows to logged-in users are
  **computed manually as a fallback** when the on-page value is missing:
  Interest Coverage, Net Profit Margin, ROA, EV/EBITDA — all derived from
  whichever "latest" row wins (which resolves to the Quarterly Results
  table, since it appears before the annual P&L on the page), so flow
  metrics are annualized ×4 against point-in-time balance-sheet figures.
- Industry P/E and relative P/E require a second network call: the page's
  `data-warehouse-id` attribute is used to hit Screener's own internal
  peers API (`/api/company/{warehouseId}/peers/`) and read the industry P/E
  out of the peer table's footer row.
- **Gotcha**: `promoterPledge` defaults to `"0%"` (not `"—"`) when it can't
  be resolved — this silently treats "no pledge data available" the same
  as "confirmed zero pledge," which could understate risk downstream.
- Result cached 24h (`@Cacheable("fundamentals")`, key = symbol) — Screener
  data doesn't change intraday, so this is safe and keeps scrape volume low.
- 404/unknown symbol surfaces as `StockNotFoundException` → HTTP 404.

## 3. Fundamental Scoring — `GET /api/stocks/{symbol}/score` and `/fundamentals/analysis`

**What it does:** turns the raw scrape into a single 0–100 score, a rating
band, and red/green flags. `/score` returns just the score; `/fundamentals/analysis`
returns fundamentals + score bundled together (this is what the frontend
actually consumes for its main dashboard).

**Behind the scenes:** see `ARCHITECTURE.md`'s "Scoring Model" section for
the full category/weight table — that reference is current and unchanged.
The short version of the mechanics:
- 10 categories (Profitability, Growth, Financial Health, Cash Flow
  Quality, Earnings Quality, Valuation, Momentum, Risk, Ownership,
  Efficiency), each computed by reweighting only the sub-metrics that are
  actually present for that stock (`ScoreUtils.weightedScore`) — this is
  why a stock missing, say, dividend data doesn't just fail, it scores
  Valuation over whatever sub-metrics *are* present.
- Categories are then combined by fixed category weight into `finalScore`,
  again only over categories that produced a result at all.
- `dataConfidence` (`N/10`) tells you how many categories actually computed
  — the one field worth checking before trusting a score on a thin-data
  stock.
- `DerivedMetricsCalculator` computes cross-field numbers that don't live
  directly on a Screener row: `relativePE`, `peg`, and **slope** metrics
  (`promoterSlope`, `institutionalSlope`). Note "slope" here is just
  `(last - first) / (n - 1)` — a two-point average rate of change, not a
  real linear regression, despite the name.
- Financial-sector companies (banks/NBFC/insurance) get a different
  sub-metric weighting within Profitability/Financial Health (opm/npm and
  debt-to-equity are skipped — not meaningful for that sector).
- Red/green flags are threshold checks over the same underlying data (e.g.
  CFO/OP < 60% → red flag, D/E ≤ 0.5 and interest coverage > 6.0 → green
  flag "Strong Balance Sheet") — see `ARCHITECTURE.md` for the full list.
- Several metrics deliberately appear in more than one category (e.g.
  `debtToEquity` in both Financial Health and Risk) — Risk is an
  intentionally-correlated downside lens, not a bug, but it means one weak
  metric can move `finalScore` more than its nominal category weight
  suggests.

## 4. Technical Indicators — `GET /api/stocks/{symbol}/indicators`

**What it does:** OHLCV price history plus computed technical indicators
(SMA, EMA, RSI, MACD, Bollinger Bands, average volume) and a "latest
snapshot" of plain-English signals.

**Behind the scenes:**
- Price data comes from **Yahoo Finance's public chart API**
  (`https://query1.finance.yahoo.com/v8/finance/chart/{TICKER}`, no key
  needed) — ticker suffix `.NS` for NSE (default) or `.BO` for BSE. `range`
  (e.g. `6mo`) is passed straight through to Yahoo's own vocabulary,
  unvalidated.
- All indicator math is **hand-rolled, no TA library**:
  - SMA: plain trailing rolling mean.
  - EMA: multiplier `2/(period+1)`, seeded with the simple average of the
    first `period` closes.
  - RSI(14): **Wilder's smoothing** (the standard used by most charting
    platforms) — average gain/loss smoothed as
    `avg = (avg*(period-1) + newValue)/period`, not a plain moving average.
  - MACD(12,26,9): `EMA12 - EMA26`, signal = `EMA9` of that difference.
  - Bollinger(20, 2σ): middle = SMA20, bands = mean ± 2 × **population**
    standard deviation (divides by N, not N-1).
- `StockAnalysisService` derives plain-English signals for the "latest"
  snapshot: `trendSignal` (Bullish/Bearish/Neutral from close vs SMA20 vs
  SMA50 ordering), `rsiSignal` (Overbought ≥70 / Oversold ≤30), `volumeSignal`
  (vs 20-day average volume).
- **Known gap**: the snapshot's `ema20` field is always `null` — EMA20 is
  computed for the full series but never plugged into the summary object.
- Cached 30 min, keyed by symbol+exchange+range.

## 5. Order Wins — `GET /api/stocks/awards`

**What it does:** a feed of BSE "Award of Order / Receipt of Order"
announcements, enriched with each company's market cap, fundamental score,
and rating where a symbol can be resolved.

**Behind the scenes:**
- Hits BSE's own (undocumented, public) announcements API directly —
  `api.bseindia.com/BseIndiaAPI/api/AnnSubCategoryGetData/w` — with headers
  spoofed to look like an XHR request from bseindia.com itself (Referer,
  X-Requested-With), since the API blocks requests that don't look
  browser-originated.
- BSE returns a headline/free-text announcement, not structured order data
  — so the amount, counterparty, and whether it's even a genuine "order"
  (vs. a tax/tribunal notice that happens to share vocabulary) are all
  **mined out of the text with regex**:
  - counterparty extracted from phrases like "order from X" / "awarded by X"
  - order amount from currency-prefixed number patterns (₹/Rs/INR + Indian
    number formatting)
  - a separate pattern detects Income Tax/GST/tribunal language and
    suppresses false-positive "awards" (forces counterparty/amount to `"—"`)
- **Company → stock symbol resolution** is its own multi-step heuristic:
  fuzzy search against the same symbol list used by `/search`, then a
  normalized-name retry (stripping "Limited/Ltd/Pvt" suffixes), then
  falling back to parsing a candidate symbol straight out of BSE's own
  article URL. First non-blank candidate wins — there's no guaranteed
  correct match.
- **Enrichment is parallel and fault-tolerant**: each row's fundamentals/score
  lookup runs on its own thread from a pooled executor, with a 12s timeout
  (deliberately longer than Screener's own 10s scrape timeout) and a
  pre-built partial/headline-only fallback response ready immediately — a
  slow or failing per-row enrichment degrades that one row, it never fails
  the whole page.
- `BSE_PAGE_SIZE` (50) is a hardcoded, undocumented assumption about BSE's
  own page size, used only to compute `totalPages` — if BSE changes it,
  pagination math goes quietly wrong.
- Cached 30 min, keyed by page/date-range/search params.

## 6. Results Calendar — `GET /api/stocks/results/upcoming` and `/results/announced`

**What it does:** two feeds — companies with an upcoming board meeting to
approve results (currently always empty, see below), and companies that
just announced results, carrying an in-house "outcome" heuristic.

**This feature no longer touches BSE at all.** It used to regex-classify
BSE's generic "Board Meeting" announcements feed (like Order Wins above);
that service (`BseResultsCalendarService`) has been deleted outright and
replaced with `ScreenerResultsCalendarService`, which reads the user's own
authenticated Screener.in account's dedicated results feed directly.

**Behind the scenes:**
- **Upcoming is always empty, on purpose.** Verified live against the
  authenticated account that Screener simply doesn't expose a
  forward-looking "upcoming results" feed — `/results/calendar/` doesn't
  exist, and both `/results/latest/` and `/announcements/results/` only
  ever show results that have *already* been declared. Rather than
  fabricate data or drop the endpoint, `fetchUpcomingResults` always
  returns an empty `PagedResult` so the frontend's Upcoming tab degrades to
  "no results" instead of erroring. Revisit if a real upcoming-results
  source ever turns up.
- **Announced results come from `/results/latest/`, queried one day at a
  time.** The page supports a per-day filter via
  `result_update_date__day/month/year` query params (confirmed live:
  different days return different company sets) but has no per-row date in
  its own markup, so each day is fetched separately and every row is
  tagged with the day it was fetched under. The caller-chosen
  `lookbackDays` is clamped to 1–2 (today alone, or today + yesterday) —
  Screener doesn't support a wider native range, and keeping row volume low
  is what lets the small enrichment pool (below) actually keep up.
  Same-symbol rows across multiple days are deduped, keeping the first
  (most recent) occurrence.
- **Each company block already carries its own latest-quarter Sales/Net
  Profit**, read straight from a `data-table` marked with
  `data-sales-latest-quarter`/`data-np-latest-quarter` attributes right on
  the results-feed page itself — these figures don't need a second trip
  through `ScreenerScraperService` the way BSE's headline-only feed did.
  Company symbol is parsed directly out of each row's `/company/<SYMBOL>/`
  link — far more reliable than BSE's old fuzzy name-matching.
- **Market cap filtering is done client-side, because Screener's own
  filter silently breaks the day filter.** `MIN_MARKET_CAP_CR=200` exists
  to skip illiquid sub-200cr names, and Screener's page supports
  `mcap=custom&mcap_min=`/`mcap_max=` query params that look like they'd do
  this server-side — but verified live that adding them makes Screener
  *silently drop the day filter entirely* and return a fixed default
  listing instead (same rows regardless of which day was requested, still
  including sub-threshold names). So market cap is instead read from each
  row's already-parsed `span[data-mcap] .strong` value and filtered in
  Java, and the day-filter query params are the only ones ever sent to
  Screener.
- **Two separate thread pools, sized for two different bottlenecks**: a
  `DAY_FETCH_EXECUTOR` (sized `max(4, min(12, cores*2))`) fans out the 1-2
  day-page fetches concurrently, while a much smaller, fixed
  `ENRICHMENT_EXECUTOR` pool of **3** runs the per-company
  `fetchFundamentals` calls used to enrich each row with a score/rating —
  empirically, Screener throttles/stalls most concurrent company-page
  requests past a handful, independent of whether the request carries the
  logged-in session cookie. Each row's enrichment gets its own 120s budget
  (`orTimeout` + `handle`, which — unlike the previous
  `completeOnTimeout`+`exceptionally` combo — lets a timeout be logged and
  distinguished from a thrown exception) and falls back to a partial
  "Insufficient Data" row rather than failing the whole page if it runs out
  the clock or the enrichment throws.
- **The "Outcome" field is still the same self-referential heuristic it
  always was, not analyst consensus** — there is no external estimates
  feed anywhere in this project. `ResultExpectationCalculator` (unchanged
  by this migration) compares the latest quarter's QoQ Net Profit growth
  against the *average* QoQ growth of all prior quarters; a swing of ≥8
  percentage points either way is "Beat Trend"/"Below Trend." This is
  explicit in the code's own comments precisely because "Beat Trend" reads
  like "beat analyst estimates" — it doesn't mean that, and the frontend
  surfaces a disclaimer tooltip for this reason. (The old BSE-era
  "Expectation" field for the *upcoming* feed no longer applies, since that
  feed is always empty now.)
- **Caching changed shape**: `resultsUpcoming` is still cached 30 min (it's
  always the same empty page, so this is a no-op in practice). But
  `resultsAnnounced` is **deliberately not cached at all anymore** — the
  old whole-page cache kept serving a page full of "Insufficient Data"
  fallback rows for the full 30-min TTL even after Screener's rate limit
  had cleared. Reliance shifted to the pre-existing per-symbol
  `fundamentals` cache (24h TTL): Spring doesn't cache thrown exceptions,
  so a symbol that failed enrichment last time is naturally retried (not
  replayed from a stale failure) on the next `/results/announced` call,
  while symbols that already succeeded just hit the fundamentals cache and
  return instantly.

## 7. AI Analysis — `GET /api/stocks/{symbol}/ai-analysis`

**What it does:** a one-shot, structured AI opinion on the stock (verdict,
overall opinion, business quality, risks, competitive advantage, earnings
summary) generated by Google Gemini.

**Behind the scenes:**
- Calls Gemini's `generateContent` REST endpoint directly (model
  `gemini-2.5-flash`, hand-built JSON — no Google SDK), single-shot, not
  streamed.
- **Full context, no curation**: the entire `FundamentalsResponse` (~70
  scraped fields) and the entire technical `LatestSnapshot` are serialized
  to JSON and handed to Gemini verbatim as the system instruction — the
  model sees essentially everything scraped, not a hand-picked summary.
- Uses Gemini's **structured output** mode (`responseMimeType:
  application/json` + a hand-authored `responseSchema`) to force the model
  to return exactly the 6 fields the response DTO expects, which are then
  deserialized directly — no prompt-parsing/regex needed on the response
  side.
- Cached 12h per symbol — justified as "this is a metered external API
  call and fundamentals/technicals don't meaningfully change intraday."
- **No fallback on failure**: unlike News below, a Gemini error or
  malformed response is not caught — it propagates to the generic 500
  handler. There's no degraded/partial AI-analysis response.

## 8. AI Chat — `POST /api/stocks/{symbol}/ai-chat`

**What it does:** a free-form follow-up chat about the stock, grounded in
the same fundamentals/technical context as AI Analysis.

**Behind the scenes:**
- Same full-context system instruction as `/ai-analysis` (whole
  fundamentals + latest technicals JSON), but with free-text output — no
  response schema, no structured-output constraint.
- **The backend is completely stateless.** There is no server-side
  conversation store: the client must send the *entire* chat history
  (`role`/`content` pairs, using Gemini's own `user`/`model` role
  vocabulary) with every single message, and the backend just replays that
  history as prior turns before appending the new message. Every call
  re-serializes and re-sends the full fundamentals+technicals context too
  — there's no session/context caching across turns.
- Not cached (each message is a fresh call, since chat responses shouldn't
  be memoized).

## 9. News — `GET /api/stocks/{symbol}/news`

**What it does:** up to 20 recent news headlines about the company.

**Behind the scenes:**
- No news API/key — pulls Google News' public RSS search feed
  (`news.google.com/rss/search?q=...`), querying `"<Company Name> stock"`
  (the company's full name, resolved via a fundamentals lookup first, not
  the bare ticker).
- Parsed as XML (JSoup with `Parser.xmlParser()`), reading each `<item>`'s
  link/pubDate/source/title, with the `" - {source}"` suffix Google News
  appends to titles stripped back off.
- **By design, fails silently**: the whole method is wrapped in one
  try/catch that logs and returns an empty list on any error — a missing
  news tab is explicitly treated as not worth surfacing as a 500, unlike
  AI Analysis's fail-loud behavior.
- Cached 15 min (shortest TTL of any cache — headlines churn through the day).

## 10. Overview — `GET /api/stocks/{symbol}/overview`

**What it does:** a lightweight combined endpoint — fundamentals plus only
the *latest* technical snapshot (not the full indicator series). Not
currently called by the frontend, which uses `/fundamentals/analysis` +
`/indicators` separately instead.

## Cross-cutting infrastructure

- **Caching** (`CacheConfig`, Caffeine, `expireAfterWrite` + max-size, no
  manual eviction endpoint): `fundamentals` (24h), `stockIndicators` (30m),
  `awardStocks`/`resultsUpcoming` (30m each), `news` (15m), `geminiAnalysis`
  (12h). `stockSearch` is registered but unused. `resultsAnnounced` was
  removed entirely — see Results Calendar above for why.
- **CORS**: `/api/**` allowed only from `localhost:3000`/`localhost:5173`
  (dev frontend ports) — no production origin configured yet.
- **Error handling**: `StockNotFoundException` → 404; everything else →
  generic 500 with the exception message, via a single
  `@RestControllerAdvice`.
- **Outbound HTTP**: two near-identical `WebClient` beans (`yahooWebClient`,
  `nseWebClient`), both just spoofing a Chrome desktop User-Agent to avoid
  being blocked by Yahoo/NSE's anti-bot defenses.

## Known issues worth remediating

- **A live Gemini API key is committed in plaintext** in
  `application.properties`, directly beneath a comment instructing it be
  set via `GEMINI_API_KEY` instead — that advice wasn't followed. Rotate
  the key and move it to an environment variable / secrets manager.
- Debug `System.out.println` calls remain in `ScreenerScraperService`'s
  production code path (symbol/fallback logging, dumping the full
  top-ratios map).
- `Untitled-1.html` / `PageSource.html` (saved Screener pages, under
  `src/main/java`, not test resources) and root-level `JsoupProbe.java`
  (a standalone `main()` scraper-debugging script, outside the Maven
  package tree) are dev fixtures, safe to leave or relocate but not part
  of the shipped app.
- BSE is now only used by `BseAwardStockService` (Order Wins) — the
  Results Calendar was fully migrated to authenticated Screener.in scraping
  (`ScreenerResultsCalendarService`/`ScreenerAuthService`), and
  `BseResultsCalendarService` was deleted rather than kept alongside it.
- `screener-credentials.properties` (the Screener username/password used
  by `ScreenerAuthService`) is a gitignored file that must exist at the
  project root for fundamentals scraping *and* the results calendar to
  work at all now — both paths go through the same authenticated session,
  so a bad/missing credential breaks both features at once, not just one.
- README documents port 8080; `application.properties` actually sets
  `server.port=8082`.
