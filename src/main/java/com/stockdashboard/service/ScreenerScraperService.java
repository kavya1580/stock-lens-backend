package com.stockdashboard.service;

import com.stockdashboard.dto.FundamentalsResponse;
import com.stockdashboard.dto.FundamentalsResponse.RatioRange;
import com.stockdashboard.dto.FundamentalsResponse.SectorInfo;
import com.stockdashboard.exception.StockNotFoundException;
import org.jsoup.Jsoup;
import org.jsoup.HttpStatusException;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


/**
 * Scrapes a single company's page on Screener.in and extracts the fundamental
 * metrics used for the personal swing-trading screen.
 *
 * NOTE ON FRAGILITY: this parses Screener's live HTML, not a stable API, so the
 * selectors below can break if Screener changes their markup. If a field starts
 * coming back as "—", open the company page in a browser, right-click the field,
 * "Inspect", and adjust the matching selector/label text here. The top-ratios
 * block (#top-ratios) has been structurally stable for years and is the safest
 * bet; the table-scanning helpers below match on visible row labels rather than
 * CSS classes, which is more resilient to markup changes.
 */
@Service
public class ScreenerScraperService {

    private static final String COMPANY_URL = "https://www.screener.in/company/%s/consolidated/";
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36";
    private static final String STANDALONE_URL = "https://www.screener.in/company/%s/";
    private static final int REQUEST_TIMEOUT_MS = 20_000;
    ;

    @Cacheable(value = "fundamentals", key = "#symbol")
    public FundamentalsResponse fetchFundamentals(String symbol) {
        Document doc = fetchDocument(symbol);
        Map<String, String> topRatios = extractTopRatios(doc);

        if (isEmpty(topRatios.get("Market Cap"))) {
            System.out.println("Consolidated page empty for " + symbol +
                    ", falling back to standalone page");

            doc = fetchStandaloneDocument(symbol);

            // IMPORTANT: recalculate ratios from the new document
            topRatios = extractTopRatios(doc);
        }

        String companyName = extractCompanyName(doc);
        System.out.println(topRatios);
        String industryPE = "—";
        String relativePE = "—";
        String pbRatio = calculatePbRatio(
                topRatios.get("Current Price"),
                topRatios.get("Book Value")
        );

        String changePercent = extractChangePercent(doc, topRatios);

        try {
            String warehouseId = extractWarehouseId(doc);

            if (warehouseId != null && !warehouseId.isBlank()) {
                Document peerDoc = fetchPeerComparison(warehouseId);

                industryPE = extractIndustryPE(peerDoc);

                double stockPEValue =
                        parseNumber(topRatios.getOrDefault("Stock P/E", "0"));

                double industryPEValue =
                        parseNumber(industryPE);

                if (industryPEValue > 0) {
                    relativePE = String.format("%.2f",
                            stockPEValue / industryPEValue);
                }
            }
        } catch (Exception ignored) {
        }

        // ── Ranges tables: Compounded Sales/Profit Growth, Stock Price CAGR, ROE ──
        // These small 4-row tables under Profit & Loss give 10Y/5Y/3Y/TTM(or latest)
        // in one parse instead of separate per-horizon lookups.
        Map<String, String> salesGrowthRanges = extractRangesTable(doc, "Compounded Sales Growth");
        Map<String, String> profitGrowthRanges = extractRangesTable(doc, "Compounded Profit Growth");
        Map<String, String> stockPriceCagrRanges = extractRangesTable(doc, "Stock Price CAGR");
        Map<String, String> roeRanges = extractRangesTable(doc, "Return on Equity");

        String salesGrowth3Y = rangeValue(salesGrowthRanges, "3 Years");
        String salesGrowth5Y = rangeValue(salesGrowthRanges, "5 Years");
        String profitGrowth3Y = rangeValue(profitGrowthRanges, "3 Years");
        String profitGrowth5Y = rangeValue(profitGrowthRanges, "5 Years");

        RatioRange salesGrowthRange = new RatioRange(
                rangeValue(salesGrowthRanges, "10 Years"), salesGrowth5Y, salesGrowth3Y,
                rangeValue(salesGrowthRanges, "TTM"));
        RatioRange profitGrowthRange = new RatioRange(
                rangeValue(profitGrowthRanges, "10 Years"), profitGrowth5Y, profitGrowth3Y,
                rangeValue(profitGrowthRanges, "TTM"));
        RatioRange stockPriceCagrRange = new RatioRange(
                rangeValue(stockPriceCagrRanges, "10 Years"),
                rangeValue(stockPriceCagrRanges, "5 Years"),
                rangeValue(stockPriceCagrRanges, "3 Years"),
                rangeValue(stockPriceCagrRanges, "1 Year"));
        RatioRange roeRange = new RatioRange(
                rangeValue(roeRanges, "10 Years"),
                rangeValue(roeRanges, "5 Years"),
                rangeValue(roeRanges, "3 Years"),
                rangeValue(roeRanges, "Last Year"));

        // ROCE doesn't have a small ranges-table — it's only in the big yearly
        // Ratios table — so build an equivalent range from that series.
        Map<String, String> roceSeries = extractRowSeries(doc, "ROCE %");
        RatioRange roceRange = buildRangeFromYearlySeries(roceSeries);

        String promoterHolding = extractLatestRowValue(doc, "Promoters", "Promoter Holding");
        String fiiHolding = extractLatestRowValue(doc, "FIIs", "Foreign Institutions", "Foreign Institutional Investors", "FII");
        String diiHolding = extractLatestRowValue(doc, "DIIs", "Domestic Institutions", "Domestic Institutional Investors", "DII");
        String publicHolding = extractLatestRowValue(doc, "Public", "Public Holding");

        String borrowings = extractLatestRowValue(doc, "Borrowings");
        String reserves = extractLatestRowValue(doc, "Reserves");
        String debtToEquity = computeDebtToEquity(doc);

        String operatingCashFlow = extractLatestRowValue(doc, "Operating Cash Flow", "Operating Cashflow", "Cash Flow from Operations", "Operating Activities", "Cash from Operating Activity");
        String netCashFlow = extractLatestRowValue(doc, "Net Cash Flow", "Net Cashflow");
        String freeCashFlow = extractLatestRowValue(doc, "Free Cash Flow", "Free Cashflow");

        Map<String, String> operatingCashFlowSeries = extractRowSeries(doc, "Cash from Operating Activity", "Operating Cash Flow", "Operating Cashflow", "Cash Flow from Operations", "Operating Activities");
        Map<String, String> netCashFlowSeries = extractRowSeries(doc, "Net Cash Flow", "Net Cashflow");
        Map<String, String> freeCashFlowSeries = extractRowSeries(doc, "Free Cash Flow", "Free Cashflow");
        Map<String, String> opmQuarterly = extractRowSeries(doc, "OPM %", "Operating Profit Margin", "OPM", "Operating Margin");
        Map<String, String> epsQuarterly = extractRowSeries(doc, "EPS in Rs", "EPS");
        Map<String, String> salesQuarterly = extractRowSeries(doc, "Sales");
        Map<String, String> netProfitQuarterly = extractRowSeries(doc, "Net Profit");
        Map<String, String> promoterHoldingQuarterly = extractRowSeries(doc, "Promoters", "Promoter Holding");
        Map<String, String> fiiHoldingQuarterly = extractRowSeries(doc, "FIIs", "Foreign Institutions", "Foreign Institutional Investors", "FII");
        Map<String, String> diiHoldingQuarterly = extractRowSeries(doc, "DIIs", "Domestic Institutions", "Domestic Institutional Investors", "DII");
        Map<String, String> publicHoldingQuarterly = extractRowSeries(doc, "Public", "Public Holding");

        String eps = extractLatestRowValue(doc, "EPS in Rs", "EPS");
        String operatingProfitMargin = extractLatestRowValue(doc, "Operating Profit Margin", "OPM", "Operating Margin");
        String netProfitMargin = extractLatestRowValue(doc, "Net Profit Margin", "NPM", "Net Margin");
        String currentRatio = extractLatestRowValue(doc, "Current Ratio");
        String interestCoverage = extractLatestRowValue(doc, "Interest Coverage", "Interest Coverage Ratio", "Interest Cover");
        String assetTurnover = extractLatestRowValue(doc, "Asset Turnover");
        String inventoryTurnover = extractLatestRowValue(doc, "Inventory Turnover");
        String receivableDays = extractLatestRowValue(doc, "Receivable Days", "Receivables Days");
        String workingCapitalDays = extractLatestRowValue(doc, "Working Capital Days");

        // ── NEW extractions ──────────────────────────────────────────────
        SectorInfo sector = extractSector(doc);

        String cfoToOperatingProfitLatest = extractLatestRowValue(doc, "CFO/OP");
        Map<String, String> cfoToOperatingProfitSeries = extractRowSeries(doc, "CFO/OP");

        String debtorDays = extractLatestRowValue(doc, "Debtor Days");
        String inventoryDays = extractLatestRowValue(doc, "Inventory Days");
        String daysPayable = extractLatestRowValue(doc, "Days Payable");
        String cashConversionCycle = extractLatestRowValue(doc, "Cash Conversion Cycle");

        String taxRateLatest = extractLatestRowValue(doc, "Tax %");
        Map<String, String> taxRateSeries = extractRowSeries(doc, "Tax %");

        String otherIncomeLatest = extractLatestRowValue(doc, "Other Income");
        Map<String, String> otherIncomeSeries = extractRowSeries(doc, "Other Income");
        String operatingProfitLatest = extractLatestRowValue(doc, "Operating Profit");

        String dividendPayoutLatest = extractLatestRowValue(doc, "Dividend Payout %", "Dividend Payout");
        Map<String, String> dividendPayoutSeries = extractRowSeries(doc, "Dividend Payout %", "Dividend Payout");
        String promoterPledge = extractLatestRowValue(doc, "Promoter Pledge", "Pledged Percentage", "Pledge %", "Promoter Holding Pledge %");

        String shareholderCount = extractShareholderCount(doc);

        List<String> pros = extractListItems(doc, "pros");
        List<String> cons = extractListItems(doc, "cons");

        // ── Computed fallbacks for ratios Screener's default markup omits ──
        // These six fields (EV/EBITDA, ROA, Net Margin, Interest Coverage,
        // Promoter Pledge — Current Ratio has no reliable path) are Screener
        // "quick ratios" a logged-in user must manually pin, so they're
        // usually absent from the scraped page. Derive them ourselves from
        // raw P&L/Balance Sheet rows that ARE always present, but only when
        // Screener's own value wasn't found — its real figure always wins.
        String marketCap = topRatios.getOrDefault("Market Cap", "—");
        String evEbitda = getTopRatioValue(topRatios, "EV/EBITDA", "EV / EBITDA", "EV Multiple", "Market Cap / Sales");
        String roa = getTopRatioValue(topRatios, "ROA", "Return on Assets", "ROA %");

        String salesLatest = extractLatestRowValue(doc, "Sales");
        String interestLatest = extractLatestRowValue(doc, "Interest");
        String netProfitLatest = extractLatestRowValue(doc, "Net Profit");
        String depreciationLatest = extractLatestRowValue(doc, "Depreciation");
        String totalAssetsLatest = extractLatestRowValue(doc, "Total Assets");

        // NOTE: extractLatestRowValue resolves to whichever table matches
        // first, which for Sales/Operating Profit/Interest/Net
        // Profit/Depreciation is Screener's Quarterly Results table (it sits
        // earlier in the page than the annual Profit & Loss table) — same
        // resolution operatingProfitLatest already relied on above. A ratio
        // of two same-quarter flows (Interest Coverage, Net Margin) is fine
        // as-is, but a flow compared against a point-in-time stock value
        // (ROA vs Total Assets, EV/EBITDA vs Market Cap) needs the quarterly
        // flow annualized (×4) first to land in the right ballpark.
        double operatingProfitValue = parseNumber(operatingProfitLatest);
        double netProfitValue = parseNumber(netProfitLatest);

        double interestValue = parseNumber(interestLatest);
        if ("—".equals(interestCoverage) && interestValue > 0) {
            interestCoverage = formatRatio(operatingProfitValue / interestValue);
        }

        double salesValue = parseNumber(salesLatest);
        if ("—".equals(netProfitMargin) && salesValue > 0) {
            netProfitMargin = formatPercent(netProfitValue / salesValue);
        }

        double totalAssetsValue = parseNumber(totalAssetsLatest);
        if ("—".equals(roa) && totalAssetsValue > 0) {
            roa = formatPercent((netProfitValue * 4) / totalAssetsValue);
        }

        double marketCapValue = parseNumber(marketCap);
        double borrowingsValue = parseNumber(borrowings);
        double depreciationValue = parseNumber(depreciationLatest);
        double annualizedEbitdaValue = (operatingProfitValue + depreciationValue) * 4;
        if ("—".equals(evEbitda) && annualizedEbitdaValue > 0) {
            evEbitda = formatRatio((marketCapValue + borrowingsValue) / annualizedEbitdaValue);
        }

        if ("—".equals(promoterPledge)) {
            promoterPledge = "0%";
        }

        return new FundamentalsResponse(
                symbol,
                companyName,
                marketCap,
                topRatios.getOrDefault("Current Price", "—"),
                changePercent,
                topRatios.getOrDefault("Stock P/E", "—"),
                relativePE,
                industryPE,
                pbRatio,
//                getTopRatioValue(topRatios, "P/B", "PB Ratio", "PB", "Price to Book"),
                evEbitda,
                topRatios.getOrDefault("Book Value", "—"),
                topRatios.getOrDefault("Dividend Yield", "—"),
                topRatios.getOrDefault("ROCE", "—"),
                topRatios.getOrDefault("ROE", "—"),
                roa,
                topRatios.getOrDefault("Face Value", "—"),
                salesGrowth3Y,
                salesGrowth5Y,
                profitGrowth3Y,
                profitGrowth5Y,
                promoterHolding,
                fiiHolding,
                diiHolding,
                publicHolding,
                borrowings,
                reserves,
                debtToEquity,
                operatingCashFlow,
                freeCashFlow,
                netCashFlow,
                eps,
                operatingProfitMargin,
                netProfitMargin,
                currentRatio,
                interestCoverage,
                assetTurnover,
                inventoryTurnover,
                receivableDays,
                workingCapitalDays,
                operatingCashFlowSeries,
                freeCashFlowSeries,
                netCashFlowSeries,
                opmQuarterly,
                epsQuarterly,
                salesQuarterly,
                netProfitQuarterly,
                promoterHoldingQuarterly,
                fiiHoldingQuarterly,
                diiHoldingQuarterly,
                publicHoldingQuarterly,
                sector,
                roceRange,
                roeRange,
                salesGrowthRange,
                profitGrowthRange,
                stockPriceCagrRange,
                cfoToOperatingProfitLatest,
                cfoToOperatingProfitSeries,
                debtorDays,
                inventoryDays,
                daysPayable,
                cashConversionCycle,
                taxRateLatest,
                taxRateSeries,
                otherIncomeLatest,
                otherIncomeSeries,
                operatingProfitLatest,
                dividendPayoutLatest,
                dividendPayoutSeries,
                promoterPledge,
                shareholderCount,
                pros,
                cons,
                String.format(COMPANY_URL, symbol)
        );
    }

    private Document fetchStandaloneDocument(String symbol) {
        try {
            return Jsoup.connect(String.format(STANDALONE_URL, symbol))
                    .userAgent(USER_AGENT)
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .timeout(10_000)
                    .get();

        } catch (IOException e) {
            throw new RuntimeException(
                    "Could not fetch standalone page for " + symbol,
                    e
            );
        }
    }

    private Document fetchDocument(String symbol) {
        try {
            return Jsoup.connect(String.format(COMPANY_URL, symbol))
                    .userAgent(USER_AGENT)
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .timeout(10_000)
                    .get();
        } catch (HttpStatusException e) {
            throw new StockNotFoundException("No fundamentals found for symbol: " + symbol);
        } catch (IOException e) {
            throw new RuntimeException("Could not reach Screener.in: " + e.getMessage(), e);
        }
    }

    private String extractCompanyName(Document doc) {
        Element h1 = doc.selectFirst("h1");
        return h1 != null ? h1.text().trim() : "Unknown";
    }

    private boolean isEmpty(String value) {
        return value == null ||
                value.isBlank() ||
                value.equals("₹") ||
                value.equals("₹ Cr.");
    }

    private Map<String, String> extractTopRatios(Document doc) {
        Map<String, String> ratios = new LinkedHashMap<>();
        Element ratiosList = doc.selectFirst("#top-ratios");
//        System.out.println("Ratiosss: " + ratiosList);
        Element marketCap = doc.selectFirst("#top-ratios .number");

        System.out.println("Market cap number = " +
                (marketCap == null ? "null" : "'" + marketCap.text() + "'"));
        System.out.println(doc.outerHtml().length());
        if (ratiosList == null) {
            return ratios;
        }
        for (Element li : ratiosList.select("li")) {
            Element nameEl = li.selectFirst(".name");
            Element valueEl = li.selectFirst(".value");
            if (nameEl != null && valueEl != null) {
                ratios.put(nameEl.text().trim(), valueEl.text().trim().replaceAll("\\s+", " "));
            }
        }
        return ratios;
    }

    /**
     * Parses the small 4-row "ranges-table" blocks under Profit & Loss:
     * Compounded Sales Growth, Compounded Profit Growth, Stock Price CAGR,
     * Return on Equity. These use class="ranges-table" — NOT the regular
     * class="data-table" used everywhere else — so they need their own parser.
     */
    private Map<String, String> extractRangesTable(Document doc, String caption) {
        Map<String, String> result = new LinkedHashMap<>();
        for (Element table : doc.select("table.ranges-table")) {
            Element header = table.selectFirst("th");
            if (header == null || !header.text().contains(caption)) {
                continue;
            }
            for (Element row : table.select("tr")) {
                Elements tds = row.select("td");
                if (tds.size() == 2) {
                    String label = tds.get(0).text().trim().replace(":", "");
                    result.put(label, tds.get(1).text().trim());
                }
            }
        }
        return result;
    }

    private String rangeValue(Map<String, String> ranges, String key) {
        return ranges.getOrDefault(key, "—");
    }

    /**
     * Builds a 10Y/5Y/3Y/latest RatioRange out of a full yearly series (used
     * for ROCE, which — unlike ROE — doesn't get its own small ranges-table
     * and only appears as a row in the big yearly Ratios table).
     */
    private RatioRange buildRangeFromYearlySeries(Map<String, String> yearlySeries) {
        if (yearlySeries.isEmpty()) {
            return new RatioRange("—", "—", "—", "—");
        }
        List<String> values = new ArrayList<>(yearlySeries.values());
        int n = values.size();
        String latest = values.get(n - 1);
        String threeYear = n >= 3 ? values.get(n - 3) : "—";
        String fiveYear = n >= 5 ? values.get(n - 5) : "—";
        String tenYear = n >= 10 ? values.get(n - 10) : "—";
        return new RatioRange(tenYear, fiveYear, threeYear, latest);
    }

    private String extractLatestRowValue(Document doc, String... rowLabels) {
        for (String rowLabel : rowLabels) {
            for (Element table : doc.select("table")) {
                for (Element row : table.select("tbody tr")) {
                    Element firstCell = row.selectFirst("td:first-child");
                    if (firstCell != null && matchesLabel(firstCell.text().trim(), rowLabel)) {
                        Elements cells = row.select("td");
                        if (!cells.isEmpty()) {
                            return cells.last().text().trim();
                        }
                    }
                }
            }
        }
        return "—";
    }

    private Map<String, String> extractRowSeries(Document doc, String... rowLabels) {
        for (String rowLabel : rowLabels) {
            for (Element table : doc.select("table")) {
                Map<String, String> values = extractSeriesFromTable(table, rowLabel);
                if (!values.isEmpty()) {
                    return values;
                }
            }
        }
        return Map.of();
    }

    private Map<String, String> extractSeriesFromTable(Element table, String rowLabel) {
        Element headerRow = table.selectFirst("thead tr");
        if (headerRow == null) {
            headerRow = table.selectFirst("tr");
        }
        if (headerRow == null) {
            return Map.of();
        }

        Elements headerCells = headerRow.select("th, td");
        if (headerCells.size() <= 1) {
            return Map.of();
        }

        Map<Integer, String> headers = new LinkedHashMap<>();
        for (int i = 1; i < headerCells.size(); i++) {
            String header = headerCells.get(i).text().trim();
            if (!header.isBlank() && isDateHeader(header)) {
                headers.put(i, header);
            }
        }

        if (headers.isEmpty()) {
            return Map.of();
        }

        for (Element row : table.select("tbody tr")) {
            Element firstCell = row.selectFirst("td:first-child, th:first-child");
            if (firstCell == null) continue;
            if (!matchesLabel(firstCell.text().trim(), rowLabel)) continue;

            Elements cells = row.select("td, th");
            Map<String, String> values = new LinkedHashMap<>();
            for (Map.Entry<Integer, String> entry : headers.entrySet()) {
                int columnIndex = entry.getKey();
                if (cells.size() > columnIndex) {
                    values.put(entry.getValue(), cells.get(columnIndex).text().trim());
                }
            }
            return values;
        }
        return Map.of();
    }

    private boolean isDateHeader(String header) {
        return header.matches("(?i)^(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)\\s+\\d{4}$")
                || header.matches("^\\d{4}$");
    }

    private boolean matchesLabel(String actual, String expected) {
        String normalizedActual = actual.toLowerCase().replaceAll("\\s+", " ");
        String normalizedExpected = expected.toLowerCase().replaceAll("\\s+", " ");
        return normalizedActual.startsWith(normalizedExpected) || normalizedActual.contains(normalizedExpected);
    }

    private String getTopRatioValue(Map<String, String> topRatios, String... names) {
        for (String name : names) {
            if (topRatios.containsKey(name)) {
                return topRatios.get(name);
            }
        }
        return "—";
    }

    private String extractChangePercent(Document doc, Map<String, String> topRatios) {
        Element changeElement = doc.selectFirst("#top .font-size-18 .up, #top .font-size-18 .down");
        if (changeElement != null) {
            String changeText = changeElement.text().trim();
            if (!changeText.isBlank()) {
                if (changeElement.hasClass("down") && !changeText.startsWith("-")) {
                    return "-" + changeText;
                }
                if (changeElement.hasClass("up") && !changeText.startsWith("+")) {
                    return "+" + changeText;
                }
                return changeText;
            }
        }

        String currentPriceRaw = topRatios.getOrDefault("Current Price", "—");
        if (currentPriceRaw != null && currentPriceRaw.contains("%")) {
            int s = currentPriceRaw.indexOf('(');
            int e = currentPriceRaw.indexOf(')', s >= 0 ? s : 0);
            if (s >= 0 && e > s) {
                return currentPriceRaw.substring(s + 1, e).trim();
            }

            int p = currentPriceRaw.indexOf('%');
            if (p > 0) {
                int start = Math.max(0, currentPriceRaw.lastIndexOf(' ', p));
                return currentPriceRaw.substring(start, p + 1).trim();
            }
        }

        String maybe = getTopRatioValue(topRatios, "Change", "Change %", "% Change", "Chg");
        if (!maybe.equals("—") && maybe.contains("%")) {
            return maybe;
        }

        return "—";
    }

    private String computeDebtToEquity(Document doc) {
        try {
            double borrowings = parseNumber(extractLatestRowValue(doc, "Borrowings"));
            double equityCapital = parseNumber(extractLatestRowValue(doc, "Equity Capital"));
            double reserves = parseNumber(extractLatestRowValue(doc, "Reserves"));
            double totalEquity = equityCapital + reserves;
            if (totalEquity == 0) return "—";
            return String.format("%.2f", borrowings / totalEquity);
        } catch (Exception e) {
            return "—";
        }
    }

    private double parseNumber(String raw) {
        if (raw == null || raw.equals("—") || raw.isBlank()) return 0;
        String cleaned = raw.replaceAll("[^0-9.\\-]", "");
        return cleaned.isEmpty() ? 0 : Double.parseDouble(cleaned);
    }

    private String formatRatio(double value) {
        return String.format("%.2fx", value);
    }

    private String formatPercent(double value) {
        return String.format("%.2f%%", value * 100);
    }

    /**
     * Sector/industry breadcrumb sits in the #peers section as a chain of
     * <a title="Broad Sector|Sector|Broad Industry|Industry"> links.
     */
    private SectorInfo extractSector(Document doc) {
        Elements links = doc.select("#peers a[title]");
        String broadSector = null, sector = null, broadIndustry = null, industry = null;
        for (Element a : links) {
            switch (a.attr("title")) {
                case "Broad Sector": broadSector = a.text().trim(); break;
                case "Sector": sector = a.text().trim(); break;
                case "Broad Industry": broadIndustry = a.text().trim(); break;
                case "Industry": industry = a.text().trim(); break;
            }
        }
        return new SectorInfo(broadSector, sector, broadIndustry, industry);
    }

    /** Footer row of the shareholding table: "No. of Shareholders". */
    private String extractShareholderCount(Document doc) {
        for (Element row : doc.select("tr.sub")) {
            Element firstCell = row.selectFirst("td:first-child");
            if (firstCell != null && matchesLabel(firstCell.text().trim(), "No. of Shareholders")) {
                Elements cells = row.select("td");
                if (!cells.isEmpty()) return cells.last().text().trim();
            }
        }
        return "—";
    }

    /** Screener's own machine-generated Pros/Cons checklist under #analysis. */
    private List<String> extractListItems(Document doc, String sectionClass) {
        Element section = doc.selectFirst("div." + sectionClass + " ul");
        List<String> items = new ArrayList<>();
        if (section == null) return items;
        for (Element li : section.select("li")) {
            String text = li.text().trim();
            if (!text.isBlank()) items.add(text);
        }
        return items;
    }
    private String extractWarehouseId(Document doc) {
        Element companyInfo = doc.getElementById("company-info");

        if (companyInfo == null) {
            return null;
        }

        return companyInfo.attr("data-warehouse-id");
    }
    private Document fetchPeerComparison(String warehouseId) {

        try {
            return Jsoup.connect(
                            "https://www.screener.in/api/company/" +
                                    warehouseId +
                                    "/peers/")
                    .userAgent(USER_AGENT)
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .timeout(10000)
                    .get();

        } catch (IOException e) {
            throw new RuntimeException(
                    "Unable to fetch peer comparison",
                    e
            );
        }
    }
    private String extractIndustryPE(Document peerDoc) {

        Element footerRow = peerDoc.selectFirst("tfoot tr");

        if (footerRow == null) {
            return "—";
        }

        Elements cells = footerRow.select("td");

        if (cells.size() < 4) {
            return "—";
        }

        return cells.get(3).text().trim();
    }
    private String calculatePbRatio(String currentPrice, String bookValue) {
        try {
            double price = parseNumber(currentPrice);
            double book = parseNumber(bookValue);

            if (book <= 0) {
                return "—";
            }

            return String.format("%.2f", price / book);
        } catch (Exception e) {
            return "—";
        }
    }
}