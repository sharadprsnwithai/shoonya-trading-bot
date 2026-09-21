package com.tradingbot.strategy.kiss.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Represents the complete persistence state of the KISS Strategy. */
@JsonIgnoreProperties(ignoreUnknown = true)
public class KissState {

    private double availableCapital = 500000.0;
    private Map<String, KissPosition> positions = new LinkedHashMap<>();
    private List<KissPosition> closedPositions = new ArrayList<>();
    private List<KissSignal> recentSignals = new ArrayList<>();
    private Instant lastScanTime;

    public KissState() {}

    public double getTotalPortfolioEquity() {
        double unrealized =
                positions.values().stream()
                        .filter(KissPosition::isActive)
                        .mapToDouble(KissPosition::getUnrealizedPnl)
                        .sum();
        return availableCapital + unrealized;
    }

    public double getAvailableCapital() {
        return availableCapital;
    }

    public void setAvailableCapital(double availableCapital) {
        this.availableCapital = availableCapital;
    }

    public Map<String, KissPosition> getPositions() {
        return positions;
    }

    public void setPositions(Map<String, KissPosition> positions) {
        this.positions = positions;
    }

    public List<KissPosition> getClosedPositions() {
        return closedPositions;
    }

    public void setClosedPositions(List<KissPosition> closedPositions) {
        this.closedPositions = closedPositions;
    }

    public List<KissSignal> getRecentSignals() {
        return recentSignals;
    }

    public void setRecentSignals(List<KissSignal> recentSignals) {
        this.recentSignals = recentSignals;
    }

    public Instant getLastScanTime() {
        return lastScanTime;
    }

    public void setLastScanTime(Instant lastScanTime) {
        this.lastScanTime = lastScanTime;
    }
}
