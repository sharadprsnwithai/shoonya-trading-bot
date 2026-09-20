package com.tradingbot.strategy.rsihighway.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Aggregate persistence state holding active positions, closed positions, recent signals, and
 * market breadth status for JSON file persistence.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class RsiHighwayState {

    private Map<String, RsiHighwayPosition> positions = new ConcurrentHashMap<>();
    private List<RsiHighwayPosition> closedPositions = new CopyOnWriteArrayList<>();
    private List<RsiHighwaySignal> recentSignals = new CopyOnWriteArrayList<>();
    private MarketBreadthSnapshot lastBreadthSnapshot;
    private Instant lastEodScanTime;
    private Instant lastMorningCheckTime;
    private double availableCapital = 1000000.0;

    public RsiHighwayState() {}

    public Map<String, RsiHighwayPosition> getPositions() {
        return positions;
    }

    public void setPositions(Map<String, RsiHighwayPosition> positions) {
        this.positions =
                positions != null ? new ConcurrentHashMap<>(positions) : new ConcurrentHashMap<>();
    }

    public List<RsiHighwayPosition> getClosedPositions() {
        return closedPositions;
    }

    public void setClosedPositions(List<RsiHighwayPosition> closedPositions) {
        this.closedPositions =
                closedPositions != null
                        ? new CopyOnWriteArrayList<>(closedPositions)
                        : new CopyOnWriteArrayList<>();
    }

    public List<RsiHighwaySignal> getRecentSignals() {
        return recentSignals;
    }

    public void setRecentSignals(List<RsiHighwaySignal> recentSignals) {
        this.recentSignals =
                recentSignals != null
                        ? new CopyOnWriteArrayList<>(recentSignals)
                        : new CopyOnWriteArrayList<>();
    }

    public MarketBreadthSnapshot getLastBreadthSnapshot() {
        return lastBreadthSnapshot;
    }

    public void setLastBreadthSnapshot(MarketBreadthSnapshot lastBreadthSnapshot) {
        this.lastBreadthSnapshot = lastBreadthSnapshot;
    }

    public Instant getLastEodScanTime() {
        return lastEodScanTime;
    }

    public void setLastEodScanTime(Instant lastEodScanTime) {
        this.lastEodScanTime = lastEodScanTime;
    }

    public Instant getLastMorningCheckTime() {
        return lastMorningCheckTime;
    }

    public void setLastMorningCheckTime(Instant lastMorningCheckTime) {
        this.lastMorningCheckTime = lastMorningCheckTime;
    }

    public double getAvailableCapital() {
        return availableCapital;
    }

    public void setAvailableCapital(double availableCapital) {
        this.availableCapital = availableCapital;
    }

    public double getTotalPortfolioEquity() {
        if (positions == null || positions.isEmpty()) {
            return Math.max(0.0, availableCapital);
        }
        double posValue =
                positions.values().stream()
                        .filter(p -> p != null && p.isActive())
                        .mapToDouble(p -> p.getTotalQuantity() * p.getAveragePrice())
                        .sum();
        return Math.max(0.0, availableCapital) + posValue;
    }
}
