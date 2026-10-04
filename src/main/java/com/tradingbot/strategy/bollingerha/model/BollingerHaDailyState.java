package com.tradingbot.strategy.bollingerha.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Tracks daily trade count, realized PnL, and circuit locks for the Bollinger HA strategy. */
public class BollingerHaDailyState {

    private LocalDate tradeDate;
    private int tradeCount;
    private BigDecimal realizedPnl;
    private boolean locked;
    private final List<BollingerHaPosition> dailyPositions;

    public BollingerHaDailyState(LocalDate tradeDate) {
        this.tradeDate = tradeDate;
        this.tradeCount = 0;
        this.realizedPnl = BigDecimal.ZERO;
        this.locked = false;
        this.dailyPositions = new ArrayList<>();
    }

    public synchronized void recordNewTrade(BollingerHaPosition position) {
        this.tradeCount++;
        this.dailyPositions.add(position);
    }

    public synchronized void addRealizedPnl(BigDecimal pnl) {
        if (pnl != null) {
            this.realizedPnl = this.realizedPnl.add(pnl);
        }
    }

    public synchronized void reset(LocalDate newDate) {
        this.tradeDate = newDate;
        this.tradeCount = 0;
        this.realizedPnl = BigDecimal.ZERO;
        this.locked = false;
        this.dailyPositions.clear();
    }

    /**
     * D4: restores persisted counters and history without re-running the incrementing bookkeeping
     * in {@link #recordNewTrade}.
     *
     * @param tradeCount trades already taken today
     * @param realizedPnl PnL already booked today
     * @param locked whether the daily circuit breaker is latched
     * @param positions today's position history
     */
    public synchronized void restore(
            int tradeCount,
            BigDecimal realizedPnl,
            boolean locked,
            List<BollingerHaPosition> positions) {
        this.tradeCount = Math.max(0, tradeCount);
        this.realizedPnl = realizedPnl != null ? realizedPnl : BigDecimal.ZERO;
        this.locked = locked;
        this.dailyPositions.clear();
        if (positions != null) {
            positions.stream().filter(Objects::nonNull).forEach(this.dailyPositions::add);
        }
    }

    public LocalDate getTradeDate() {
        return tradeDate;
    }

    public int getTradeCount() {
        return tradeCount;
    }

    public BigDecimal getRealizedPnl() {
        return realizedPnl;
    }

    public boolean isLocked() {
        return locked;
    }

    public void setLocked(boolean locked) {
        this.locked = locked;
    }

    public List<BollingerHaPosition> getDailyPositions() {
        return new ArrayList<>(dailyPositions);
    }
}
