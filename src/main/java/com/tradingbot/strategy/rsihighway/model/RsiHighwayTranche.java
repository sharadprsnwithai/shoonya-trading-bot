package com.tradingbot.strategy.rsihighway.model;

import java.time.Instant;

/**
 * Record representing a single executed entry tranche (1, 2, or 3) for a position.
 */
public record RsiHighwayTranche(
        int trancheNumber,
        int quantity,
        double entryPrice,
        Instant entryTime,
        String orderId
) {}
