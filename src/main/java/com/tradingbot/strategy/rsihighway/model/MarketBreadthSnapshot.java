package com.tradingbot.strategy.rsihighway.model;

import java.time.Instant;
import java.util.List;

/** Snapshot representing the broader market regime and 52-week high leadership breadth. */
public record MarketBreadthSnapshot(
        boolean isHighwayOpen,
        int totalUniverseScanned,
        int leadersNear52WeekHighCount,
        List<String> leadersList,
        double indexDrawdownPct,
        String reason,
        Instant evaluatedAt) {}
