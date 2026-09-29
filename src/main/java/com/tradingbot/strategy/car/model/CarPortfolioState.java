package com.tradingbot.strategy.car.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class CarPortfolioState {

    private BigDecimal initialCapital;
    private BigDecimal totalCapital;
    private BigDecimal realizedPnL;
    private int numParts;
    private BigDecimal profitTargetPct;
    private Map<String, CarHolding> holdings = new ConcurrentHashMap<>();
    private Map<String, CarGttOrder> gttOrders = new ConcurrentHashMap<>();

    public CarPortfolioState() {
        this(new BigDecimal("1000000.0"), 40, new BigDecimal("6.28"));
    }

    public CarPortfolioState(BigDecimal initialCapital, int numParts, BigDecimal profitTargetPct) {
        this.initialCapital = initialCapital != null ? initialCapital : new BigDecimal("1000000.0");
        this.totalCapital = this.initialCapital;
        this.realizedPnL = BigDecimal.ZERO;
        this.numParts = numParts > 0 ? numParts : 40;
        this.profitTargetPct = profitTargetPct != null ? profitTargetPct : new BigDecimal("6.28");
    }

    public synchronized void addFill(String symbol, int quantity, BigDecimal fillPrice) {
        if (symbol == null || quantity <= 0 || fillPrice == null) return;
        CarHolding existing = holdings.get(symbol);
        if (existing == null) {
            holdings.put(symbol, CarHolding.initial(symbol, quantity, fillPrice, profitTargetPct));
        } else {
            holdings.put(symbol, existing.withAdditionalFill(quantity, fillPrice, profitTargetPct));
        }
    }

    public synchronized BigDecimal closeHoldingAtTarget(String symbol, BigDecimal exitPrice) {
        CarHolding holding = holdings.remove(symbol);
        if (holding == null) return BigDecimal.ZERO;

        BigDecimal pnl =
                exitPrice
                        .subtract(holding.averageBuyPrice())
                        .multiply(BigDecimal.valueOf(holding.totalQuantity()))
                        .setScale(2, RoundingMode.HALF_UP);

        this.realizedPnL = this.realizedPnL.add(pnl);
        this.totalCapital = this.totalCapital.add(pnl);
        return pnl;
    }

    @JsonIgnore
    public BigDecimal getUnitSize() {
        return totalCapital.divide(BigDecimal.valueOf(numParts), 2, RoundingMode.HALF_UP);
    }

    @JsonIgnore
    public int getAvailableUnits() {
        int investedUnits = holdings.values().stream().mapToInt(CarHolding::accumulatedUnits).sum();
        long pendingBuyGtts =
                gttOrders.values().stream()
                        .filter(
                                g ->
                                        g.type() == GttOrderType.BUY
                                                && g.status() == GttStatus.PENDING)
                        .count();
        return Math.max(0, numParts - investedUnits - (int) pendingBuyGtts);
    }

    public CarHolding getHolding(String symbol) {
        return holdings.get(symbol);
    }

    public BigDecimal getInitialCapital() {
        return initialCapital;
    }

    public void setInitialCapital(BigDecimal initialCapital) {
        this.initialCapital = initialCapital;
    }

    public BigDecimal getTotalCapital() {
        return totalCapital;
    }

    public void setTotalCapital(BigDecimal totalCapital) {
        this.totalCapital = totalCapital;
    }

    public BigDecimal getRealizedPnL() {
        return realizedPnL;
    }

    public void setRealizedPnL(BigDecimal realizedPnL) {
        this.realizedPnL = realizedPnL;
    }

    public int getNumParts() {
        return numParts;
    }

    public void setNumParts(int numParts) {
        this.numParts = numParts;
    }

    public BigDecimal getProfitTargetPct() {
        return profitTargetPct;
    }

    public void setProfitTargetPct(BigDecimal profitTargetPct) {
        this.profitTargetPct = profitTargetPct;
    }

    public Map<String, CarHolding> getHoldings() {
        return holdings;
    }

    public void setHoldings(Map<String, CarHolding> holdings) {
        this.holdings = holdings;
    }

    public Map<String, CarGttOrder> getGttOrders() {
        return gttOrders;
    }

    public void setGttOrders(Map<String, CarGttOrder> gttOrders) {
        this.gttOrders = gttOrders;
    }
}
