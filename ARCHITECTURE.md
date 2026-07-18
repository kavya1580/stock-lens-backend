# Architecture Reference

Standing reference for this codebase so future work can read this file
instead of re-reading source across `controller/`, `service/`, `dto/`.
Endpoints and run instructions live in `README.md` — not duplicated here.

## Package Map

**`controller/`**
- `StockController` — the only `@RestController`, base path `/api/stocks`. Wires every service below into 7 endpoints (see README for request/response shape):
  - `GET /search` → `StockSearchService`
  - `GET /{symbol}/indicators` → `StockAnalysisService` (technical/OHLCV)
  - `GET /{symbol}/fundamentals` → `ScreenerScraperService` (raw scrape only)
  - `GET /{symbol}/fundamentals/analysis` → scrape + `FundamentalScoreService` combined (this is the `/fundamentalanalysis`-style endpoint discussed when this doc was written)
  - `GET /{symbol}/score` → scrape + score, score only
  - `GET /awards` → `BseAwardStockService`
  - `GET /{symbol}/overview` → fundamentals + indicator *summary* (not full series — that's what `/indicators` is for)

**`service/`**
- `ScreenerScraperService` — JSoup scraper for `screener.in/company/<SYMBOL>`. Builds `FundamentalsResponse`. Matches table rows by **label text**, not stable CSS selectors/IDs — fragile to Screener markup changes (see Gotchas).
- `FundamentalScoreService` — thin orchestration wrapper: calls `ScoreCalculator.analyze()`, adapts `ScoreAnalysis` into `FundamentalScoreResponse`.
- `ScoreCalculator` — the scoring engine. 10 weighted categories → `finalScore`, plus red/green flags. See "Scoring Model" below.
- `ScoreUtils` — stateless helper library: `parseDouble`/`parseSeriesValues`/`isValid` (string→number parsing with graceful failure), `interpolate(value, thresholds[][])` (piecewise-linear scoring), `linearSlope(series)`, `weightedScore(scores, weights)` (reweights over only the sub-metrics actually present — this is *why* missing data degrades gracefully instead of erroring), and one `scoreXxx`/`computeXxx` function per sub-metric.
- `DerivedMetricsCalculator` — computes cross-field metrics that don't live directly on a Screener row: `relativePE`, `peg`, `promoterSlope`, `institutionalSlope` (linear slope of the FII+DII sum series — the authoritative institutional-trend number, see Gotchas), plus the human-readable `derivedMetrics` trend labels shown in the API response.
- `StockAnalysisService` / `YahooFinanceService` / `IndicatorService` — technical side: OHLCV fetch from Yahoo Finance, indicator computation (RSI, MACD, Bollinger, etc.). Independent of the Screener/fundamentals path.
- `StockSearchService` — ticker search/autocomplete.
- `BseAwardStockService` — BSE award-stocks listing, unrelated to fundamentals scoring.
- `ScoreAnalysis` — internal record carrying `ScoreCalculator`'s full result (breakdowns, finalScore, rating, dataConfidence, flags, derivedMetrics) before it's adapted into the public `FundamentalScoreResponse` DTO.

**`dto/`** — API response records. Notable ones: `FundamentalsResponse` (raw scrape, ~60 fields, positional record — see Gotchas), `FundamentalScoreResponse`/`ScoreBreakdown`/`RedFlag`/`GreenFlag` (scoring output), `FundamentalAnalysisResponse` (fundamentals + score combined), `StockOverviewResponse`, `StockIndicatorResponse`, `LatestSnapshot`.

**`config/`** — `CorsConfig`, `WebClientConfig`, `CacheConfig`, `OpenApiConfig`. Standard Spring Boot wiring, nothing scoring-specific.

**`exception/`** — `StockNotFoundException` + `GlobalExceptionHandler` (`@ControllerAdvice`).

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

- **`Untitled-1.html`** (`service/Untitled-1.html`) and **`PageSource.html`** (`com/stockdashboard/PageSource.html`) are saved Screener.in pages sitting under `src/main/java` (not `src/test/resources`). They are dev fixtures used to eyeball scraper output against real HTML — not wired into any test or build step, and safe to ignore (or relocate) but not delete without checking they aren't referenced elsewhere first. `Untitled-1.html` is an unrelated NBFC ("Indus Finance Ltd") page, not Lupin.
- **`ScreenerScraperService` matches by label text and document order**, not stable selectors. `extractLatestRowValue`/`extractRowSeries` scan for a row whose first cell matches one of the given label strings; `extractRowSeries`'s quarterly-vs-yearly disambiguation for some tables depends on which table appears first in the page, not an explicit id/class. A Screener markup reshuffle can silently break extraction (falls back to `"—"`/absent rather than throwing, per the `isValid`/`weightedScore` graceful-degradation pattern — but that also means breakage is silent unless you check `dataConfidence`).
- **`FundamentalsResponse` is a positional Java record.** Its field order must exactly match the constructor call in `ScreenerScraperService.fetchFundamentals`. Adding/reordering a field requires updating both in lockstep — there's no named-argument safety net.
- **Some metrics are scored in more than one category**, so their real influence on `finalScore` exceeds their nominal category weight: `debtToEquity`/`interestCoverage` appear in both Financial Health and Risk; `promoterHolding` appears in both Ownership (`promoterLevel`) and Risk (`ownershipRisk`); `otherIncomeDependency` appears in both Earnings Quality and Risk; `cashConversionCycle` appears in both Efficiency and Risk. This is intentional (Risk is a deliberately-correlated downside lens) but worth remembering when explaining why a single weak metric moved the final score more than its category weight suggests.
- **No `mvn`/`mvnw` binary was available** in the shell environment this doc was written in — scoring-logic changes here were verified by hand-tracing against a captured LUPIN `/fundamentals` response, not by a real build. Run `mvn spring-boot:run` (or build via IntelliJ) after any change here before trusting it compiles.
