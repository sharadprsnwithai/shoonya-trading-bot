package com.tradingbot.strategy.bollingerha.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

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
