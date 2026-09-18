package com.tradingbot.model.strategy;

import java.util.List;

/**
 * Encapsulates the 09:25 IST market sentiment, winning sector, and candidate stock pool.
 */
public record LowestVolumeSectorState(
        int advances,
        int declines,
        LowestVolumeDirection sentiment,
        String topSector,
        double sectorPctChange,
        List<String> candidateSymbols) {

    public static LowestVolumeSectorState empty() {
        return new LowestVolumeSectorState(
                0, 0, LowestVolumeDirection.NONE, "", 0.0, List.of());
    }
}
