package com.tradingbot.strategy.breakoutvwap.service;

import com.tradingbot.marketdata.HistoricalOhlcCacheService;
import com.tradingbot.marketdata.NseBanPeriodClient;
import com.tradingbot.marketdata.NseOiSpurtClient;
import com.tradingbot.marketdata.ShoonyaMarketDataService;
import com.tradingbot.marketdata.feed.ShoonyaFeedClient;
import com.tradingbot.model.Candle;
import com.tradingbot.model.NseOiSpurtItem;
import com.tradingbot.model.strategy.StockQuoteSnapshot;
import com.tradingbot.strategy.breakoutvwap.config.BvsProperties;
import com.tradingbot.strategy.breakoutvwap.model.BvsBucket;
import com.tradingbot.strategy.breakoutvwap.model.BvsUniverse;
import com.tradingbot.util.NseTradingCalendarUtil;
import com.tradingbot.util.StockFnoRegistry;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Builds the 12-stock BVS universe at 09:25 IST: 3 live gainers, 3 live losers, 3 OI spurts and 3
 * three-day range contractions, all F&amp;O-eligible, de-duplicated across buckets, ban-listed
 * symbols excluded. A failed ban fetch fails closed — the strategy stands down for the day.
 */
@Service
@Slf4j
public class BvsUniverseScanner {

    private final BvsProperties properties;
    private final ShoonyaFeedClient feedClient;
    private final ShoonyaMarketDataService marketDataService;
    private final NseOiSpurtClient oiSpurtClient;
    private final NseBanPeriodClient banPeriodClient;
    private final HistoricalOhlcCacheService ohlcCacheService;

    /** One-shot OI-spurt list staged by the 09:25:05 prefetch and consumed by the 09:25:25 scan. */
    private volatile List<NseOiSpurtItem> stagedOiItems;

    public BvsUniverseScanner(
            BvsProperties properties,
            ShoonyaFeedClient feedClient,
            ShoonyaMarketDataService marketDataService,
            NseOiSpurtClient oiSpurtClient,
            NseBanPeriodClient banPeriodClient,
            HistoricalOhlcCacheService ohlcCacheService) {
        this.properties = properties;
        this.feedClient = feedClient;
        this.marketDataService = marketDataService;
        this.oiSpurtClient = oiSpurtClient;
        this.banPeriodClient = banPeriodClient;
        this.ohlcCacheService = ohlcCacheService;
    }

    /** Stages the staggered prefetch result; the next scan consumes it once, then falls back. */
    void stageOiItems(List<NseOiSpurtItem> items) {
        this.stagedOiItems = items;
    }

    /**
     * Builds the day's selection. Never throws: each bucket degrades independently; an empty result
     * means the strategy stands down.
     *
     * @return the selected universe, possibly empty
     */
    public BvsUniverse scan() {
        Set<String> rawBanned = banPeriodClient.fetchBanList();
        if (!banPeriodClient.isLastFetchSuccessful()) {
            log.warn(
                    "BVS stand-down: ban list unavailable, refusing to select an unverified universe");
            return BvsUniverse.empty();
        }
        Set<String> banned = rawBanned == null ? Set.of() : rawBanned;

        List<String> candidates = candidateUniverse();
        Set<String> candidateSet = Set.copyOf(candidates);

        List<String> rankedGainers = List.of();
        List<String> rankedLosers = List.of();
        try {
            Map<String, StockQuoteSnapshot> quotes =
                    feedClient.snapshots(candidates, Duration.ofSeconds(30));
            if (quotes != null && !quotes.isEmpty()) {
                List<StockQuoteSnapshot> fresh =
                        quotes.values().stream()
                                .filter(
                                        q ->
                                                q.symbol() != null
                                                        && candidateSet.contains(q.symbol()))
                                .toList();
                rankedGainers = topByPctChange(fresh, fresh.size(), true);
                rankedLosers = topByPctChange(fresh, fresh.size(), false);
            } else {
                log.warn("BVS degrade: no fresh feed quotes, gainers/losers buckets empty");
            }
        } catch (RuntimeException e) {
            log.warn(
                    "BVS degrade: feed quote fetch failed ({}), gainers/losers buckets empty",
                    e.getMessage());
        }

        List<NseOiSpurtItem> oiItems = stagedOiItems;
        stagedOiItems = null; // one-shot: a staged prefetch serves exactly one scan
        if (oiItems == null) {
            try {
                oiItems = oiSpurtClient.fetchTopUnderlyings(properties.getBucketSize() * 2);
            } catch (RuntimeException e) {
                log.warn(
                        "BVS degrade: OI-spurt fetch failed ({}), OI_SPURT bucket empty",
                        e.getMessage());
                oiItems = List.of();
            }
        }
        List<String> rankedOi = new ArrayList<>();
        if (oiItems != null) {
            for (NseOiSpurtItem item : oiItems) {
                if (item != null && candidateSet.contains(item.symbol())) {
                    rankedOi.add(item.symbol());
                }
            }
        }

        List<String> squeezePool = squeezeCandidates(candidates);

        Map<BvsBucket, List<String>> byBucket = new EnumMap<>(BvsBucket.class);
        LinkedHashSet<String> taken = new LinkedHashSet<>();
        byBucket.put(BvsBucket.GAINERS, selectBucket(rankedGainers, banned, taken));
        byBucket.put(BvsBucket.LOSERS, selectBucket(rankedLosers, banned, taken));
        byBucket.put(BvsBucket.OI_SPURT, selectBucket(rankedOi, banned, taken));
        byBucket.put(BvsBucket.SQUEEZE, selectBucket(squeezePool, banned, taken));

        BvsUniverse universe = new BvsUniverse(byBucket);
        if (universe.allSymbols().isEmpty()) {
            log.warn("BVS stand-down: no bucket produced symbols");
        }
        return universe;
    }

    private List<String> candidateUniverse() {
        List<String> candidates = new ArrayList<>();
        for (String symbol : StockFnoRegistry.getAllInstruments().keySet()) {
            if (!StockFnoRegistry.isIndex(symbol)) {
                candidates.add(symbol);
            }
        }
        return candidates;
    }

    /**
     * Three-day contractions, in registry order, capped at twice the bucket size so a large
     * registry cannot stall the scan while still leaving spare picks for ban/dedupe backfill.
     */
    private List<String> squeezeCandidates(List<String> candidates) {
        List<String> pool = new ArrayList<>();
        int cap = properties.getBucketSize() * 2;
        LocalDate today = LocalDate.now(NseTradingCalendarUtil.IST_ZONE);
        for (String symbol : candidates) {
            try {
                List<Candle> candles = ohlcCacheService.getDailyCandles(symbol);
                if (candles == null || candles.isEmpty()) {
                    candles = marketDataService.fetchDailyCandles(symbol, 10);
                }
                if (candles == null) {
                    continue;
                }
                List<Candle> completed =
                        candles.stream()
                                .filter(
                                        c ->
                                                c.timestamp() != null
                                                        && !LocalDate.ofInstant(
                                                                        c.timestamp(),
                                                                        NseTradingCalendarUtil
                                                                                .IST_ZONE)
                                                                .equals(today))
                                .toList();
                if (isThreeDayContraction(completed)) {
                    pool.add(symbol);
                    if (pool.size() >= cap) {
                        break;
                    }
                }
            } catch (RuntimeException e) {
                log.warn("BVS degrade: squeeze scan failed for {} ({})", symbol, e.getMessage());
            }
        }
        return pool;
    }

    private List<String> selectBucket(
            List<String> ranked, Set<String> banned, LinkedHashSet<String> taken) {
        List<String> selected = new ArrayList<>();
        for (String symbol : ranked) {
            if (selected.size() >= properties.getBucketSize()) {
                break;
            }
            if (banned.contains(symbol) || taken.contains(symbol)) {
                continue;
            }
            selected.add(symbol);
            taken.add(symbol);
        }
        return List.copyOf(selected);
    }

    /**
     * Ranks symbols by percent change over the F&amp;O universe.
     *
     * @param quotes quote snapshots to rank
     * @param n maximum number of symbols to return
     * @param gainers true for descending (top gainers), false for ascending (top losers)
     * @return the top {@code n} symbols in rank order
     */
    public static List<String> topByPctChange(
            Collection<StockQuoteSnapshot> quotes, int n, boolean gainers) {
        Comparator<StockQuoteSnapshot> order =
                Comparator.comparingDouble(StockQuoteSnapshot::pctChange);
        if (gainers) {
            order = order.reversed();
        }
        return quotes.stream()
                .filter(q -> q.symbol() != null && !q.symbol().isBlank())
                .sorted(order.thenComparing(StockQuoteSnapshot::symbol))
                .limit(Math.max(0, n))
                .map(StockQuoteSnapshot::symbol)
                .toList();
    }

    /**
     * Three-day range contraction: for each of the last three daily transitions the high prints
     * lower than the prior session's high and the low prints higher than the prior session's low.
     *
     * @param candles completed daily candles, oldest first (today's bar already stripped)
     * @return true when the last three sessions narrowed on both sides
     */
    public static boolean isThreeDayContraction(List<Candle> candles) {
        if (candles == null || candles.size() < 4) {
            return false;
        }
        int size = candles.size();
        for (int i = size - 3; i < size; i++) {
            Candle prev = candles.get(i - 1);
            Candle curr = candles.get(i);
            if (prev == null
                    || curr == null
                    || prev.high() == null
                    || curr.high() == null
                    || prev.low() == null
                    || curr.low() == null) {
                return false;
            }
            boolean lowerHigh = curr.high().compareTo(prev.high()) < 0;
            boolean higherLow = curr.low().compareTo(prev.low()) > 0;
            if (!lowerHigh || !higherLow) {
                return false;
            }
        }
        return true;
    }
}
