package com.tradingbot.strategy.ttcstraddle.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Tracks the daily session state, open legs, phase, and kill-switch status. */
public class StraddleDailyState {

    private final LocalDate tradeDate;
    private final String instrument;
    private volatile StraddleSessionPhase phase = StraddleSessionPhase.IDLE;
    private volatile boolean killSwitchTripped = false;
    private volatile BigDecimal realizedPnl = BigDecimal.ZERO;

    private volatile StraddleLeg hedgeLongCe;
    private volatile StraddleLeg hedgeLongPe;
    private volatile StraddleLeg shortCe;
    private volatile StraddleLeg shortPe;

    public StraddleDailyState(LocalDate tradeDate, String instrument) {
        this.tradeDate = tradeDate;
        this.instrument = instrument;
    }

    public LocalDate getTradeDate() {
        return tradeDate;
    }

    public String getInstrument() {
        return instrument;
    }

    public StraddleSessionPhase getPhase() {
        return phase;
    }

    public void setPhase(StraddleSessionPhase phase) {
        this.phase = phase;
    }

    public boolean isKillSwitchTripped() {
        return killSwitchTripped;
    }

    public void setKillSwitchTripped(boolean killSwitchTripped) {
        this.killSwitchTripped = killSwitchTripped;
    }

    public BigDecimal getRealizedPnl() {
        return realizedPnl;
    }

    public synchronized void addRealizedPnl(BigDecimal pnl) {
        if (pnl != null) {
            this.realizedPnl = this.realizedPnl.add(pnl);
        }
    }

    public StraddleLeg getHedgeLongCe() {
        return hedgeLongCe;
    }

    public void setHedgeLongCe(StraddleLeg hedgeLongCe) {
        this.hedgeLongCe = hedgeLongCe;
    }

    public StraddleLeg getHedgeLongPe() {
        return hedgeLongPe;
    }

    public void setHedgeLongPe(StraddleLeg hedgeLongPe) {
        this.hedgeLongPe = hedgeLongPe;
    }

    public StraddleLeg getShortCe() {
        return shortCe;
    }

    public void setShortCe(StraddleLeg shortCe) {
        this.shortCe = shortCe;
    }

    public StraddleLeg getShortPe() {
        return shortPe;
    }

    public void setShortPe(StraddleLeg shortPe) {
        this.shortPe = shortPe;
    }
}
