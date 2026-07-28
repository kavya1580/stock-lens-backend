# Stock Dashboard Backend

Spring Boot backend for a personal Indian-stocks (NSE/BSE) research dashboard.
It combines technical indicators (Yahoo Finance OHLCV), fundamentals
(Screener.in), AI-generated analysis (Gemini), an order-wins/BSE-awards feed,
and a results calendar into one API consumed by the [`stock-lens`](../stock-lens)
React frontend.

See [ARCHITECTURE.md](ARCHITECTURE.md) for how the pieces fit together and
[FUNCTIONALITY.md](FUNCTIONALITY.md) for a feature-by-feature walkthrough.

## Features

- **Technical analysis** — OHLCV history plus SMA/EMA/RSI/MACD/Bollinger Bands,
  computed in-process from Yahoo Finance data.
- **Fundamental analysis** — metrics scraped from Screener.in, scored across
  10 weighted categories (profitability, growth, solvency, valuation, etc.)
  into a single `finalScore` with red/green flags.
- **AI analysis & chat** — Gemini-generated narrative analysis and follow-up
  Q&A grounded in a stock's fundamentals and technicals.
- **Order wins** — BSE corporate-announcement feed filtered to
  order/contract-win filings, enriched with fundamentals and a live progress
  indicator while the enrichment fan-out is in flight.
- **Results calendar** — upcoming and recently-announced quarterly results,
  scraped from an authenticated Screener.in session, with an expected-direction
  heuristic based on a company's trailing profit trend.
- **News** — recent headlines per stock via Google News RSS.

## Prerequisites

- JDK 23
- Maven
- A [Screener.in](https://www.screener.in) account (free) — the results
  calendar logs in as you to reach pages that are gated behind auth
- A free [Gemini API key](https://aistudio.google.com/apikey) — only needed
  for the AI Analysis tab

## Setup

1. **Screener.in credentials** — create `screener-credentials.properties` in
   the project root (this file is gitignored and never committed):

   ```properties
   screener.username=you@example.com
   screener.password=your-password
   ```

2. **Gemini API key** — set it as an environment variable before starting the
   app:

   ```
   export GEMINI_API_KEY=your-key-here
   ```

   The AI Analysis/chat endpoints will return errors without it; every other
   endpoint works fine if it's left unset.

3. **Run it**

   ```
   mvn spring-boot:run
   ```

   Starts on `http://localhost:8082`.

   ```
   curl http://localhost:8082/api/stocks/TCS/overview
   ```

## API

All endpoints are under `/api/stocks`. Swagger UI is available at
`http://localhost:8082/swagger-ui.html` once the app is running.

```
GET  /search?q={query}                                  -> symbol/name search
GET  /{symbol}/indicators?exchange=&range=               -> full OHLCV + indicator history
GET  /{symbol}/fundamentals                              -> Screener.in metrics
GET  /{symbol}/fundamentals/analysis                     -> fundamentals + score breakdown
GET  /{symbol}/score                                     -> score breakdown only
GET  /{symbol}/overview?exchange=&range=                 -> fundamentals + latest technical snapshot
GET  /{symbol}/ai-analysis?exchange=&range=              -> Gemini-generated structured analysis
POST /{symbol}/ai-chat?exchange=&range=                  -> follow-up AI chat turn (body: history + message)
GET  /{symbol}/news                                      -> recent headlines (Google News RSS)

GET  /awards?pageNo=&prevDate=&toDate=&search=           -> BSE order-win announcements, enriched
GET  /awards/progress                                    -> live enrichment progress for the above

GET  /results/upcoming?pageNo=                           -> upcoming quarterly results
GET  /results/announced?pageNo=&lookbackDays=            -> recently announced quarterly results
GET  /results/announced/progress                         -> live enrichment progress for the above
```

## Project structure

```
src/main/java/com/stockdashboard/
├── config/       Spring configuration (CORS, cache, WebClient, OpenAPI)
├── controller/    REST endpoints (StockController)
├── dto/          Request/response records
├── exception/    Global exception handling
└── service/      Scraping, indicator/score computation, AI, enrichment tracking
```

## Notes

- Fundamentals and results-calendar data are scraped from Screener.in HTML,
  which means selectors are hand-tuned against live markup and can break if
  Screener changes their page structure — see ARCHITECTURE.md's "Known
  Gotchas" for specifics.
- The Screener.in login automation is for personal use against your own
  account at a low request volume; it is not intended for redistribution or
  scaled scraping.
- `app.cors.allowed-origins` in `application.properties` defaults to local
  dev ports (`3000`, `5173`) — set it to your deployed frontend's origin
  before hosting this publicly.
