package com.tradingbot.positional.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Encapsulates the persisted state for the Positional Trading Strategy. */
public class PositionalState {

    private PositionalStatus status;
    private PositionalAlert activeAlert;
    private PositionalTrade activeTrade;
    private Instant lastUpdated;
    private List<PositionalTrade> historicalTrades;

    public PositionalState() {
        this.status = PositionalStatus.FLAT;
        this.activeAlert = null;
        this.activeTrade = null;
        this.lastUpdated = Instant.now();
        this.historicalTrades = new ArrayList<>();
    }

    @JsonCreator
    public PositionalState(
            @JsonProperty("status") PositionalStatus status,
            @JsonProperty("activeAlert") PositionalAlert activeAlert,
            @JsonProperty("activeTrade") PositionalTrade activeTrade,
            @JsonProperty("lastUpdated") Instant lastUpdated,
            @JsonProperty("historicalTrades") List<PositionalTrade> historicalTrades) {
        this.status = status != null ? status : PositionalStatus.FLAT;
        this.activeAlert = activeAlert;
        this.activeTrade = activeTrade;
        this.lastUpdated = lastUpdated != null ? lastUpdated : Instant.now();
        this.historicalTrades =
                historicalTrades != null ? new ArrayList<>(historicalTrades) : new ArrayList<>();
    }

    public PositionalStatus getStatus() {
        return status;
    }

    public void setStatus(PositionalStatus status) {
        this.status = status;
        this.lastUpdated = Instant.now();
    }

    public PositionalAlert getActiveAlert() {
        return activeAlert;
    }

    public void setActiveAlert(PositionalAlert activeAlert) {
        this.activeAlert = activeAlert;
        this.lastUpdated = Instant.now();
    }

    public PositionalTrade getActiveTrade() {
        return activeTrade;
    }

    public void setActiveTrade(PositionalTrade activeTrade) {
        this.activeTrade = activeTrade;
        this.lastUpdated = Instant.now();
    }

    public Instant getLastUpdated() {
        return lastUpdated;
    }

    public void setLastUpdated(Instant lastUpdated) {
        this.lastUpdated = lastUpdated;
    }

    public List<PositionalTrade> getHistoricalTrades() {
        return Collections.unmodifiableList(historicalTrades);
    }

    public void addHistoricalTrade(PositionalTrade trade) {
        if (trade != null) {
            this.historicalTrades.add(trade);
            this.lastUpdated = Instant.now();
        }
    }
}
