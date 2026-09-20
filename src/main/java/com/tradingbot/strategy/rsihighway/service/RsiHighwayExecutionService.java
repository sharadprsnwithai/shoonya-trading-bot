package com.tradingbot.strategy.rsihighway.service;

import com.tradingbot.model.order.OrderRequest;
import com.tradingbot.model.order.OrderResponse;
import com.tradingbot.model.order.OrderType;
import com.tradingbot.model.order.ProductType;
import com.tradingbot.model.order.TransactionType;
import com.tradingbot.order.ShoonyaOrderService;
import com.tradingbot.strategy.rsihighway.config.RsiHighwayConfig;
import com.tradingbot.strategy.rsihighway.model.RsiHighwayPosition;
import com.tradingbot.strategy.rsihighway.model.RsiHighwaySignal;
import com.tradingbot.strategy.rsihighway.model.RsiHighwayTranche;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Handles position sizing, paper execution simulation, and live Shoonya CNC order placement for the
 * RSI Highway Multi-Timeframe Swing Strategy.
 */
@Service
public class RsiHighwayExecutionService {

    private static final Logger log = LoggerFactory.getLogger(RsiHighwayExecutionService.class);

    private final ShoonyaOrderService orderService;
    private final RsiHighwayConfig config;

    public RsiHighwayExecutionService(ShoonyaOrderService orderService, RsiHighwayConfig config) {
        this.orderService = orderService;
        this.config = config;
    }

    /**
     * Computes the shares to buy based on risk-per-trade budget and single stock capital cap.
     *
     * @param entryPrice Entry price / trigger price
     * @param slPrice Initial stop loss price
     * @param accountCapital Total trading account capital
     * @param rptPercent Risk Per Trade percent (e.g. 1.0 for 1%)
     * @param maxCapitalPercent Maximum allowed portfolio capital per stock (e.g. 10.0 for 10%)
     * @return Integer number of shares to purchase
     */
    public int calculatePositionSize(
            double entryPrice,
            double slPrice,
            double accountCapital,
            double rptPercent,
            double maxCapitalPercent) {

        if (entryPrice <= 0 || accountCapital <= 0) {
            return 0;
        }

        double minRiskDistance = Math.max(0.05, 0.005 * entryPrice);
        double riskPerShare = Math.max(minRiskDistance, entryPrice - slPrice);
        double maxRiskBudget = accountCapital * (rptPercent / 100.0);
        int qtyByRisk = (int) Math.floor(maxRiskBudget / riskPerShare);

        double maxCapitalBudget = accountCapital * (maxCapitalPercent / 100.0);
        int qtyByCapital = (int) Math.floor(maxCapitalBudget / entryPrice);

        int allowedQty = Math.min(qtyByRisk, qtyByCapital);
        return Math.max(0, allowedQty);
    }

    /**
     * Executes an entry signal (Tranche 1, 2, or 3) in paper or live mode.
     *
     * @param signal Generated buy signal
     * @param availableCapital Capital available for sizing
     * @return Executed RsiHighwayTranche or empty on failure
     */
    public Optional<RsiHighwayTranche> executeEntrySignal(
            RsiHighwaySignal signal, double availableCapital) {
        return executeEntrySignal(signal, availableCapital, availableCapital);
    }

    /**
     * Executes an entry signal (Tranche 1, 2, or 3) sized by total portfolio equity and constrained
     * by available cash.
     *
     * @param signal Generated buy signal
     * @param portfolioEquity Total portfolio equity for percentage-based position sizing
     * @param availableCash Cash balance available for execution
     * @return Executed RsiHighwayTranche or empty on failure
     */
    public Optional<RsiHighwayTranche> executeEntrySignal(
            RsiHighwaySignal signal, double portfolioEquity, double availableCash) {
        if (signal == null) return Optional.empty();

        double equity = portfolioEquity > 0 ? portfolioEquity : config.getPaperCapital();
        double cash = availableCash > 0 ? availableCash : equity;

        int baseQty =
                calculatePositionSize(
                        signal.triggerPrice(),
                        signal.initialSlPrice(),
                        equity,
                        config.getRiskPerTradePercent(),
                        config.getMaxCapitalPerStockPercent());

        if (baseQty <= 0) {
            log.warn(
                    "[RSI-HIGHWAY] Position size computed as 0 for {} (Equity ₹{}, Price ₹{}). Skipping entry.",
                    signal.symbol(),
                    equity,
                    signal.triggerPrice());
            return Optional.empty();
        }

        // Adjust quantity for pyramiding tranches (Tranche 1: 100%, Tranche 2: 50%, Tranche 3: 25%)
        int trancheQty = baseQty;
        if (signal.trancheNumber() == 2) {
            trancheQty = Math.max(1, (int) Math.round(baseQty * 0.50));
        } else if (signal.trancheNumber() == 3) {
            trancheQty = Math.max(1, (int) Math.round(baseQty * 0.25));
        }

        // Check against available cash and cap if necessary
        double totalRequiredCapital = trancheQty * signal.triggerPrice();
        if (totalRequiredCapital > cash) {
            int affordableQty = (int) Math.floor(cash / signal.triggerPrice());
            if (affordableQty <= 0) {
                log.warn(
                        "[RSI-HIGHWAY] Required capital ₹{} exceeds available cash ₹{} for {}. Skipping entry.",
                        totalRequiredCapital,
                        cash,
                        signal.symbol());
                return Optional.empty();
            }
            log.info(
                    "[RSI-HIGHWAY] Capping tranche qty from {} to {} based on available cash ₹{} for {}",
                    trancheQty,
                    affordableQty,
                    cash,
                    signal.symbol());
            trancheQty = affordableQty;
        }

        String orderId = "RSI_HW_" + UUID.randomUUID().toString().substring(0, 8);
        Instant executionTime = signal.generatedAt() != null ? signal.generatedAt() : Instant.now();

        if (config.isPaperTrading()) {
            log.info(
                    "[PAPER-EXECUTION] Filled {} Tranche {} for {} shares @ ₹{} (SL: ₹{}). OrderId: {}",
                    signal.symbol(),
                    signal.trancheNumber(),
                    trancheQty,
                    signal.triggerPrice(),
                    signal.initialSlPrice(),
                    orderId);
            return Optional.of(
                    new RsiHighwayTranche(
                            signal.trancheNumber(),
                            trancheQty,
                            signal.triggerPrice(),
                            executionTime,
                            orderId));
        }

        // Live Execution on Shoonya (CNC / Delivery)
        try {
            String tradingSymbol = resolveTradingSymbol(signal.symbol(), "NSE");
            OrderRequest req =
                    new OrderRequest(
                            tradingSymbol,
                            "NSE",
                            TransactionType.BUY,
                            OrderType.MKT,
                            ProductType.CNC,
                            trancheQty,
                            BigDecimal.valueOf(signal.triggerPrice()),
                            null,
                            "RSI_HIGHWAY");

            OrderResponse resp = orderService.placeOrder(req);
            if (resp != null && resp.success()) {
                String brokerOrderId = resp.orderId() != null ? resp.orderId() : orderId;
                log.info(
                        "[LIVE-EXECUTION] Placed Shoonya CNC Buy for {} ({}) qty {} @ ₹{}. Broker OrderId: {}",
                        signal.symbol(),
                        tradingSymbol,
                        trancheQty,
                        signal.triggerPrice(),
                        brokerOrderId);
                return Optional.of(
                        new RsiHighwayTranche(
                                signal.trancheNumber(),
                                trancheQty,
                                signal.triggerPrice(),
                                executionTime,
                                brokerOrderId));
            } else {
                log.error(
                        "[LIVE-EXECUTION] Order placement failed for {}: {}",
                        signal.symbol(),
                        resp != null ? resp.message() : "null response");
                return Optional.empty();
            }
        } catch (Exception e) {
            log.error("[LIVE-EXECUTION] Exception placing live order for {}", signal.symbol(), e);
            return Optional.empty();
        }
    }

    /**
     * Executes a complete position exit across all tranches.
     *
     * @param position Active position to close
     * @param exitPrice Current market exit price
     * @param reason Exit reason (e.g. "Daily RSI < 50 Close", "Emergency Plunge")
     * @return true if exit successfully processed
     */
    public boolean executeExit(RsiHighwayPosition position, double exitPrice, String reason) {
        if (position == null || !position.isActive()) return false;

        String orderId = "EXIT_HW_" + UUID.randomUUID().toString().substring(0, 8);

        if (config.isPaperTrading()) {
            position.setActive(false);
            position.setLastEvaluatedAt(Instant.now());
            log.info(
                    "[PAPER-EXIT] Closed 100% position in {} ({} shares) @ ₹{}. Reason: {}. OrderId: {}",
                    position.getSymbol(), position.getTotalQuantity(), exitPrice, reason, orderId);
            return true;
        }

        // Live Execution on Shoonya (Sell CNC)
        try {
            String exchange = position.getExchange() != null ? position.getExchange() : "NSE";
            String tradingSymbol = resolveTradingSymbol(position.getSymbol(), exchange);
            OrderRequest req =
                    new OrderRequest(
                            tradingSymbol,
                            exchange,
                            TransactionType.SELL,
                            OrderType.MKT,
                            ProductType.CNC,
                            position.getTotalQuantity(),
                            BigDecimal.valueOf(exitPrice),
                            null,
                            "RSI_HIGHWAY_EXIT");

            OrderResponse resp = orderService.placeOrder(req);
            if (resp != null && resp.success()) {
                position.setActive(false);
                position.setLastEvaluatedAt(Instant.now());
                log.info(
                        "[LIVE-EXIT] Shoonya Sell filled for {} ({}) ({} shares) @ ₹{}. Broker OrderId: {}",
                        position.getSymbol(),
                        tradingSymbol,
                        position.getTotalQuantity(),
                        exitPrice,
                        resp.orderId());
                return true;
            } else {
                log.error(
                        "[LIVE-EXIT] Failed to place Shoonya Sell for {}: {}",
                        position.getSymbol(),
                        resp != null ? resp.message() : "null");
                return false;
            }
        } catch (Exception e) {
            log.error(
                    "[LIVE-EXIT] Exception closing live position for {}", position.getSymbol(), e);
            return false;
        }
    }

    private String resolveTradingSymbol(String symbol, String exchange) {
        if (symbol == null) return "";
        String clean = symbol.trim();
        if (clean.startsWith("NSE:")) {
            clean = clean.substring(4);
        }
        if ("NSE".equalsIgnoreCase(exchange)
                && !clean.endsWith("-EQ")
                && !clean.startsWith("NIFTY")) {
            return clean + "-EQ";
        }
        return clean;
    }
}
