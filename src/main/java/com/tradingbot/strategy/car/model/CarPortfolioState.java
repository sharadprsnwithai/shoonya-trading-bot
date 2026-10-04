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
    private java.time.LocalDate lastRunWeek;
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

    /**
     * Capital units still free for new entries.
     *
     * <p>Both legs are measured in <em>capital</em> rather than by counting entries, because a
     * single share of an expensive stock ties up several units while still registering as one
     * holding: counting entries would let the strategy overspend, and releasing exactly one unit
     * per entry would never give that capital back.
     */
    @JsonIgnore
    public int getAvailableUnits() {
        BigDecimal unit = getUnitSize();
        if (unit.signum() <= 0) {
            return 0;
        }

        int investedUnits = 0;
        for (CarHolding holding : holdings.values()) {
            if (holding == null || holding.totalQuantity() <= 0) continue;
            investedUnits +=
                    unitsFor(
                            BigDecimal.valueOf(holding.totalQuantity())
                                    .multiply(holding.averageBuyPrice()),
                            unit);
        }

        int reservedUnits = 0;
        for (CarGttOrder order : gttOrders.values()) {
            if (order == null
                    || order.type() != GttOrderType.BUY
                    || order.status() != GttStatus.PENDING
                    || order.quantity() <= 0
                    || order.limitPrice() == null) {
                continue;
            }
            reservedUnits +=
                    unitsFor(
                            BigDecimal.valueOf(order.quantity()).multiply(order.limitPrice()),
                            unit);
        }

        return Math.max(0, numParts - investedUnits - reservedUnits);
    }

    private static int unitsFor(BigDecimal amount, BigDecimal unitSize) {
        if (amount == null || amount.signum() <= 0) {
            return 0;
        }
        java.math.BigInteger units =
                amount.divide(unitSize, 0, RoundingMode.CEILING).toBigInteger();
        if (units.compareTo(java.math.BigInteger.valueOf(Integer.MAX_VALUE)) > 0) {
            return Integer.MAX_VALUE;
        }
        return Math.max(1, units.intValue());
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

    /** Monday of the last week the weekly routine completed, used to keep runs idempotent. */
    public java.time.LocalDate getLastRunWeek() {
        return lastRunWeek;
    }

    public void setLastRunWeek(java.time.LocalDate lastRunWeek) {
        this.lastRunWeek = lastRunWeek;
    }
}
