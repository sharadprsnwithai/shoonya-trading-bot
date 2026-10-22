package com.tradingbot.strategy.breakoutvwap.persistence;

import com.tradingbot.strategy.breakoutvwap.model.BvsBucket;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The persisted slice of BVS daily state — the day's setups, open positions, trade history, the
 * stand-down latch and the selected universe. Public fields for direct Jackson binding. Fields
 * added after the first release are absent from older snapshot files; Jackson leaves them at their
 * Java defaults.
 */
public class BvsDailyState {

    /** The trading day this snapshot describes, so a same-day restart stays same-day. */
    public LocalDate date;

    public Instant savedAt;

    public Map<String, BvsSetupSnapshot> setups = new LinkedHashMap<>();

    public List<BvsPositionSnapshot> openPositions = new ArrayList<>();

    public List<BvsPositionSnapshot> tradeHistory = new ArrayList<>();

    /** True when the ban/feed failure stood the strategy down for this day. */
    public boolean stoodDownToday;

    /** The 09:25 selection by bucket, so a restart keeps the day's universe. */
    public Map<BvsBucket, List<String>> universeByBucket = new LinkedHashMap<>();
}
