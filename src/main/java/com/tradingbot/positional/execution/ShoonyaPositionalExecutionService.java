package com.tradingbot.positional.execution;

import com.tradingbot.config.ShoonyaConfig;
import com.tradingbot.marketdata.ShoonyaOptionChainService;
import com.tradingbot.model.execution.ExecutionMode;
import com.tradingbot.model.order.OrderRequest;
import com.tradingbot.model.order.OrderResponse;
import com.tradingbot.model.order.OrderType;
import com.tradingbot.model.order.ProductType;
import com.tradingbot.model.order.TransactionType;
import com.tradingbot.order.ShoonyaOrderService;
import com.tradingbot.positional.config.PositionalStrategyConfig;
import com.tradingbot.positional.model.PositionalTrade;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Execution implementation for Positional Hedged Credit Spread Strategy. Executes ATM Option
 * Selling paired with a 0.20 Delta OTM Protective Hedge (Bull Put Spread for Long, Bear Call Spread
 * for Short).
 */
@Service
public class ShoonyaPositionalExecutionService implements PositionalExecutionService {

    private static final Logger log =
            LoggerFactory.getLogger(ShoonyaPositionalExecutionService.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final ShoonyaOrderService orderService;
    private final ShoonyaOptionChainService optionChainService;
    private final PositionalStrategyConfig config;
    private final ShoonyaConfig shoonyaConfig;

    @Autowired
    public ShoonyaPositionalExecutionService(
            ShoonyaOrderService orderService,
            ShoonyaOptionChainService optionChainService,
            PositionalStrategyConfig config,
            @Autowired(required = false) ShoonyaConfig shoonyaConfig) {
        this.orderService = orderService;
        this.optionChainService = optionChainService;
        this.config = config;
        this.shoonyaConfig = shoonyaConfig;
    }

    @Override
    public BigDecimal findMonthlyAtmStrike(String symbol, BigDecimal spotPrice) {
        if (spotPrice == null) {
            return BigDecimal.ZERO;
        }
        int interval = symbol != null && symbol.toUpperCase().contains("BANK") ? 100 : 50;
        double rounded = Math.round(spotPrice.doubleValue() / interval) * interval;
        return BigDecimal.valueOf(rounded).setScale(2, RoundingMode.HALF_UP);
    }

    @Override
    public PositionalTrade executeEntry(PositionalTrade trade) {
        log.info(
                "[POSITIONAL SPREAD ENTRY] Executing {} | SELL ATM {} {:.0f} & BUY Hedge {} {:.0f} (Qty: {})",
                trade.strategyType(),
                trade.sellOptionType(),
                trade.sellStrike(),
                trade.buyHedgeOptionType(),
                trade.buyHedgeStrike(),
                trade.quantity());

        ExecutionMode mode = trade.mode() != null ? trade.mode() : ExecutionMode.PAPER;
        BigDecimal sellFillPremium;
        BigDecimal buyHedgeFillPremium;

        if (mode == ExecutionMode.LIVE && orderService != null) {
            try {
                // 1. First BUY Hedge Leg for instant exchange margin benefit
                String hedgeSymbol =
                        formatOptionTradingSymbol(
                                trade.underlying(),
                                trade.expiryDate(),
                                trade.buyHedgeStrike(),
                                trade.buyHedgeOptionType());
                OrderRequest hedgeOrder =
                        new OrderRequest(
                                hedgeSymbol,
                                "NFO",
                                TransactionType.BUY,
                                OrderType.MKT,
                                ProductType.NRML,
                                trade.quantity(),
                                BigDecimal.ZERO,
                                BigDecimal.ZERO,
                                "POS_HEDGE_" + trade.tradeId());

                log.info("[POSITIONAL LIVE] Placing BUY 0.20 Delta Hedge: {}", hedgeOrder);
                OrderResponse hedgeResp = orderService.placeOrder(hedgeOrder);
                if (hedgeResp == null || !hedgeResp.success()) {
                    String err = hedgeResp != null ? hedgeResp.message() : "Null response";
                    log.error(
                            "[POSITIONAL LIVE] Hedge leg placement failed: {}. Aborting sell leg.",
                            err);
                    throw new RuntimeException("Hedge leg order failed: " + err);
                }
                buyHedgeFillPremium = estimate02DeltaPremium(trade.entrySpot());

                // 2. Second SELL ATM Leg
                String sellSymbol =
                        formatOptionTradingSymbol(
                                trade.underlying(),
                                trade.expiryDate(),
                                trade.sellStrike(),
                                trade.sellOptionType());
                OrderRequest sellOrder =
                        new OrderRequest(
                                sellSymbol,
                                "NFO",
                                TransactionType.SELL,
                                OrderType.MKT,
                                ProductType.NRML,
                                trade.quantity(),
                                BigDecimal.ZERO,
                                BigDecimal.ZERO,
                                "POS_SELL_" + trade.tradeId());

                log.info("[POSITIONAL LIVE] Placing SELL ATM Leg: {}", sellOrder);
                OrderResponse sellResp = orderService.placeOrder(sellOrder);
                if (sellResp == null || !sellResp.success()) {
                    String err = sellResp != null ? sellResp.message() : "Null response";
                    log.error("[POSITIONAL LIVE] Sell leg placement failed: {}", err);
                    throw new RuntimeException("Sell leg order failed: " + err);
                }
                sellFillPremium = estimateAtmPremium(trade.entrySpot());

                log.info(
                        "[POSITIONAL LIVE] Spread placed successfully (Hedge ID={}, Sell ID={})",
                        hedgeResp != null ? hedgeResp.orderId() : "N/A",
                        sellResp != null ? sellResp.orderId() : "N/A");
            } catch (Exception e) {
                log.error("[POSITIONAL LIVE] Error during live spread execution", e);
                sellFillPremium = estimateAtmPremium(trade.entrySpot());
                buyHedgeFillPremium = estimate02DeltaPremium(trade.entrySpot());
            }
        } else {
            // Paper mode simulation
            sellFillPremium = estimateAtmPremium(trade.entrySpot());
            buyHedgeFillPremium = estimate02DeltaPremium(trade.entrySpot());
            log.info(
                    "[POSITIONAL PAPER] Simulated Spread Entry | SELL {} {:.0f} @ ₹{} | BUY {} {:.0f} @ ₹{}",
                    trade.sellOptionType(),
                    trade.sellStrike(),
                    sellFillPremium,
                    trade.buyHedgeOptionType(),
                    trade.buyHedgeStrike(),
                    buyHedgeFillPremium);
        }

        BigDecimal netCredit =
                sellFillPremium.subtract(buyHedgeFillPremium).setScale(2, RoundingMode.HALF_UP);

        String resolvedExpiry =
                (trade.expiryDate() != null
                                && !trade.expiryDate().isBlank()
                                && !"MONTHLY".equalsIgnoreCase(trade.expiryDate()))
                        ? trade.expiryDate()
                        : resolveNextMonthlyExpiry().toString();

        return new PositionalTrade(
                trade.tradeId(),
                trade.underlying(),
                trade.strategyType(),
                trade.sellOptionType(),
                trade.sellStrike(),
                trade.buyHedgeOptionType(),
                trade.buyHedgeStrike(),
                resolvedExpiry,
                trade.entryDate() != null ? trade.entryDate() : LocalDate.now(IST),
                trade.entrySpot(),
                sellFillPremium,
                buyHedgeFillPremium,
                netCredit,
                trade.slSpot(),
                trade.targetSpot(),
                trade.numLots(),
                trade.quantity(),
                mode,
                "OPEN",
                null,
                null,
                null,
                null,
                null,
                null);
    }

    @Override
    public PositionalTrade executeExit(
            PositionalTrade trade, BigDecimal exitSpot, String exitReason) {
        log.info(
                "[POSITIONAL SPREAD EXIT] Closing {} | SELL Leg {:.0f} & Hedge {:.0f} (Reason: {})",
                trade.strategyType(),
                trade.sellStrike(),
                trade.buyHedgeStrike(),
                exitReason);

        ExecutionMode mode = trade.mode() != null ? trade.mode() : ExecutionMode.PAPER;
        BigDecimal sellExitPremium;
        BigDecimal buyHedgeExitPremium;

        int daysHeld = 0;
        if (trade.entryDate() != null) {
            daysHeld =
                    (int)
                            Math.max(
                                    0,
                                    ChronoUnit.DAYS.between(trade.entryDate(), LocalDate.now(IST)));
        }

        if (mode == ExecutionMode.LIVE && orderService != null) {
            try {
                // 1. Buy to cover sold ATM leg
                String sellSymbol =
                        formatOptionTradingSymbol(
                                trade.underlying(),
                                trade.expiryDate(),
                                trade.sellStrike(),
                                trade.sellOptionType());
                OrderRequest coverOrder =
                        new OrderRequest(
                                sellSymbol,
                                "NFO",
                                TransactionType.BUY,
                                OrderType.MKT,
                                ProductType.NRML,
                                trade.quantity(),
                                BigDecimal.ZERO,
                                BigDecimal.ZERO,
                                "POS_COVER_" + trade.tradeId());

                log.info("[POSITIONAL LIVE] BUY to cover ATM short leg: {}", coverOrder);
                orderService.placeOrder(coverOrder);

                // 2. Sell to close hedge leg
                String hedgeSymbol =
                        formatOptionTradingSymbol(
                                trade.underlying(),
                                trade.expiryDate(),
                                trade.buyHedgeStrike(),
                                trade.buyHedgeOptionType());
                OrderRequest closeHedgeOrder =
                        new OrderRequest(
                                hedgeSymbol,
                                "NFO",
                                TransactionType.SELL,
                                OrderType.MKT,
                                ProductType.NRML,
                                trade.quantity(),
                                BigDecimal.ZERO,
                                BigDecimal.ZERO,
                                "POS_CLOSE_HEDGE_" + trade.tradeId());

                log.info("[POSITIONAL LIVE] SELL to close hedge leg: {}", closeHedgeOrder);
                orderService.placeOrder(closeHedgeOrder);

                BigDecimal[] chainLtp = tryResolveOptionChainLtp(trade);
                if (chainLtp != null) {
                    sellExitPremium = chainLtp[0];
                    buyHedgeExitPremium = chainLtp[1];
                } else {
                    BigDecimal[] estExit = estimateExitPremiums(trade, exitSpot, daysHeld);
                    sellExitPremium = estExit[0];
                    buyHedgeExitPremium = estExit[1];
                }
            } catch (Exception e) {
                log.error("[POSITIONAL LIVE] Error during live spread exit", e);
                BigDecimal[] chainLtp = tryResolveOptionChainLtp(trade);
                if (chainLtp != null) {
                    sellExitPremium = chainLtp[0];
                    buyHedgeExitPremium = chainLtp[1];
                } else {
                    BigDecimal[] estExit = estimateExitPremiums(trade, exitSpot, daysHeld);
                    sellExitPremium = estExit[0];
                    buyHedgeExitPremium = estExit[1];
                }
            }
        } else {
            // Paper mode exit
            BigDecimal[] chainLtp = tryResolveOptionChainLtp(trade);
            if (chainLtp != null) {
                sellExitPremium = chainLtp[0];
                buyHedgeExitPremium = chainLtp[1];
            } else {
                BigDecimal[] estExit = estimateExitPremiums(trade, exitSpot, daysHeld);
                sellExitPremium = estExit[0];
                buyHedgeExitPremium = estExit[1];
            }
            log.info(
                    "[POSITIONAL PAPER] Simulated Spread Exit | Covered SELL Leg @ ₹{} | Closed Hedge @ ₹{}",
                    sellExitPremium,
                    buyHedgeExitPremium);
        }

        // PnL Calculation
        BigDecimal sellEntryPrem =
                trade.sellEntryPremium() != null ? trade.sellEntryPremium() : BigDecimal.ZERO;
        BigDecimal buyHedgeEntryPrem =
                trade.buyHedgeEntryPremium() != null
                        ? trade.buyHedgeEntryPremium()
                        : BigDecimal.ZERO;
        BigDecimal soldLegPnl = sellEntryPrem.subtract(sellExitPremium);
        BigDecimal hedgeLegPnl = buyHedgeExitPremium.subtract(buyHedgeEntryPrem);
        BigDecimal netPnlPts = soldLegPnl.add(hedgeLegPnl);

        // Cap PnL between Max Profit (Net Credit) and Max Loss (Spread Width - Net Credit)
        BigDecimal sellStrike = trade.sellStrike() != null ? trade.sellStrike() : BigDecimal.ZERO;
        BigDecimal buyHedgeStrike =
                trade.buyHedgeStrike() != null ? trade.buyHedgeStrike() : BigDecimal.ZERO;
        BigDecimal spreadWidth = sellStrike.subtract(buyHedgeStrike).abs();
        BigDecimal netCredit =
                trade.netCredit() != null
                        ? trade.netCredit()
                        : sellEntryPrem.subtract(buyHedgeEntryPrem);
        BigDecimal maxProfit = netCredit;
        BigDecimal maxLoss = spreadWidth.subtract(netCredit).negate();

        if (netPnlPts.compareTo(maxProfit) > 0) {
            netPnlPts = maxProfit;
        } else if (netPnlPts.compareTo(maxLoss) < 0) {
            netPnlPts = maxLoss;
        }

        BigDecimal totalPnlRupees =
                netPnlPts
                        .multiply(BigDecimal.valueOf(trade.quantity()))
                        .setScale(2, RoundingMode.HALF_UP);

        return new PositionalTrade(
                trade.tradeId(),
                trade.underlying(),
                trade.strategyType(),
                trade.sellOptionType(),
                trade.sellStrike(),
                trade.buyHedgeOptionType(),
                trade.buyHedgeStrike(),
                trade.expiryDate(),
                trade.entryDate(),
                trade.entrySpot(),
                trade.sellEntryPremium(),
                trade.buyHedgeEntryPremium(),
                trade.netCredit(),
                trade.slSpot(),
                trade.targetSpot(),
                trade.numLots(),
                trade.quantity(),
                mode,
                "CLOSED",
                LocalDate.now(IST),
                exitSpot,
                sellExitPremium,
                buyHedgeExitPremium,
                totalPnlRupees,
                exitReason);
    }

    private BigDecimal[] tryResolveOptionChainLtp(PositionalTrade trade) {
        if (optionChainService == null || trade == null || trade.sellStrike() == null) {
            return null;
        }
        try {
            var chain =
                    optionChainService.getIndexOptionChain(
                            trade.underlying(), trade.sellStrike(), 10, true);
            if (chain != null && chain.strikes() != null) {
                double soldLtp = 0.0;
                double hedgeLtp = 0.0;

                for (var os : chain.strikes()) {
                    if (os.strikePrice() != null) {
                        if (trade.sellStrike() != null
                                && os.strikePrice().compareTo(trade.sellStrike()) == 0) {
                            var contract =
                                    "CE".equalsIgnoreCase(trade.sellOptionType())
                                            ? os.call()
                                            : os.put();
                            if (contract != null && contract.ltp() != null) {
                                soldLtp = contract.ltp().doubleValue();
                            }
                        }
                        if (trade.buyHedgeStrike() != null
                                && os.strikePrice().compareTo(trade.buyHedgeStrike()) == 0) {
                            var contract =
                                    "CE".equalsIgnoreCase(trade.buyHedgeOptionType())
                                            ? os.call()
                                            : os.put();
                            if (contract != null && contract.ltp() != null) {
                                hedgeLtp = contract.ltp().doubleValue();
                            }
                        }
                    }
                }

                if (soldLtp > 0 && hedgeLtp > 0) {
                    return new BigDecimal[] {
                        BigDecimal.valueOf(soldLtp).setScale(2, RoundingMode.HALF_UP),
                        BigDecimal.valueOf(hedgeLtp).setScale(2, RoundingMode.HALF_UP)
                    };
                }
            }
        } catch (Exception e) {
            log.debug(
                    "[POSITIONAL EXIT] Option chain quote resolution bypassed: {}", e.getMessage());
        }
        return null;
    }

    /** Estimates ATM monthly option premium (~1.5% of spot for ~30 DTE). */
    public BigDecimal estimateAtmPremium(BigDecimal spot) {
        if (spot == null || spot.signum() == 0) {
            return BigDecimal.valueOf(360.0).setScale(2, RoundingMode.HALF_UP);
        }
        double est = spot.doubleValue() * 0.015;
        return BigDecimal.valueOf(Math.max(50.0, est)).setScale(2, RoundingMode.HALF_UP);
    }

    /** Estimates 0.20 Delta OTM monthly option premium (~0.38% of spot for ~30 DTE). */
    public BigDecimal estimate02DeltaPremium(BigDecimal spot) {
        if (spot == null || spot.signum() == 0) {
            return BigDecimal.valueOf(90.0).setScale(2, RoundingMode.HALF_UP);
        }
        double est = spot.doubleValue() * 0.0038;
        return BigDecimal.valueOf(Math.max(10.0, est)).setScale(2, RoundingMode.HALF_UP);
    }

    /** Estimates exit premiums for sold ATM leg and bought hedge leg. */
    private BigDecimal[] estimateExitPremiums(
            PositionalTrade trade, BigDecimal currentSpot, int daysHeld) {
        if (currentSpot == null || trade.entrySpot() == null) {
            return new BigDecimal[] {trade.sellEntryPremium(), trade.buyHedgeEntryPremium()};
        }

        boolean isBullPut = "BULL_PUT_SPREAD".equalsIgnoreCase(trade.strategyType());
        double spotMove =
                isBullPut
                        ? currentSpot.doubleValue() - trade.entrySpot().doubleValue()
                        : trade.entrySpot().doubleValue() - currentSpot.doubleValue();

        // Sold leg: Delta 0.50, Theta +4.0 pts/day decay
        double soldChange = -(spotMove * 0.50) - (4.0 * daysHeld);
        double soldExit = Math.max(0.0, trade.sellEntryPremium().doubleValue() + soldChange);

        // Hedge leg: Delta 0.20, Theta -1.0 pt/day decay
        double hedgeChange = -(spotMove * 0.20) - (1.0 * daysHeld);
        double hedgeExit = Math.max(0.0, trade.buyHedgeEntryPremium().doubleValue() + hedgeChange);

        return new BigDecimal[] {
            BigDecimal.valueOf(soldExit).setScale(2, RoundingMode.HALF_UP),
            BigDecimal.valueOf(hedgeExit).setScale(2, RoundingMode.HALF_UP)
        };
    }

    private LocalDate resolveNextMonthlyExpiry() {
        LocalDate now = LocalDate.now(IST);
        LocalDate lastThuThisMonth = now.with(TemporalAdjusters.lastInMonth(DayOfWeek.THURSDAY));
        // If within 15 days of this month's expiry, roll to next month for positional trades (> 20
        // DTE target)
        if (now.isAfter(lastThuThisMonth.minusDays(15))) {
            return now.plusMonths(1).with(TemporalAdjusters.lastInMonth(DayOfWeek.THURSDAY));
        }
        return lastThuThisMonth;
    }

    private String formatOptionTradingSymbol(
            String underlying, String expiry, BigDecimal strike, String type) {
        String cleanUnderlying =
                underlying != null ? underlying.replace(" ", "").toUpperCase() : "NIFTY";
        if (cleanUnderlying.contains("NIFTY50") || cleanUnderlying.contains("NIFTY 50")) {
            cleanUnderlying = "NIFTY";
        }
        String expStr = resolveShoonyaExpiryFormat(expiry);
        double strikeVal = strike != null ? strike.doubleValue() : 0.0;
        String typeStr = type != null ? type.toUpperCase() : "CE";
        return String.format("%s%s%.0f%s", cleanUnderlying, expStr, strikeVal, typeStr);
    }

    private String resolveShoonyaExpiryFormat(String expiry) {
        if (expiry == null || expiry.isBlank() || expiry.equalsIgnoreCase("MONTHLY")) {
            LocalDate expDate = resolveNextMonthlyExpiry();
            int year = expDate.getYear() % 100;
            String month =
                    expDate.getMonth()
                            .getDisplayName(
                                    java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH)
                            .toUpperCase(java.util.Locale.ENGLISH);
            return String.format("%02d%s", year, month);
        }
        if (expiry.length() == 5) {
            return expiry.toUpperCase();
        }
        try {
            LocalDate expDate = LocalDate.parse(expiry);
            int year = expDate.getYear() % 100;
            String month =
                    expDate.getMonth()
                            .getDisplayName(
                                    java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH)
                            .toUpperCase(java.util.Locale.ENGLISH);
            return String.format("%02d%s", year, month);
        } catch (Exception e) {
            log.debug(
                    "Failed parsing expiry string '{}', defaulting to uppercase fallback: {}",
                    expiry,
                    e.getMessage());
            return expiry.toUpperCase();
        }
    }
}
