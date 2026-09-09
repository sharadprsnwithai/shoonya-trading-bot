package com.tradingbot.execution;

import com.tradingbot.config.ShoonyaConfig;
import com.tradingbot.marketdata.ShoonyaOptionChainService;
import com.tradingbot.model.OptionChainResponse;
import com.tradingbot.model.OptionContract;
import com.tradingbot.model.OptionStrike;
import com.tradingbot.model.execution.ActiveSpreadPosition;
import com.tradingbot.model.execution.ExecutionMode;
import com.tradingbot.model.order.OrderRequest;
import com.tradingbot.model.order.OrderResponse;
import com.tradingbot.model.order.OrderType;
import com.tradingbot.model.order.ProductType;
import com.tradingbot.model.order.TransactionType;
import com.tradingbot.order.ShoonyaOrderService;
import com.tradingbot.telegram.TelegramService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Execution Manager handling Directional Option Selling with: 1. Deep OTM Protective Hedge Leg
 * (Black Swan Defense & Margin Reduction). 2. Instant Broker-Level SL-L (Stop-Loss Limit) Order
 * Placement upon Short fill. 3. PAPER vs LIVE Execution Mode Toggle.
 */
@Service
public class ExecutionManager {

    private static final Logger log = LoggerFactory.getLogger(ExecutionManager.class);
    public static final int DEFAULT_HEDGE_STRIKE_OFFSET = 150; // 150 pts OTM credit spread hedge

    @Value("${trading-bot.strategy.pivot-supertrend.hedge-distance:150}")
    private int hedgeStrikeOffset = DEFAULT_HEDGE_STRIKE_OFFSET;

    public static final double SL_MULTIPLIER = 1.40; // +40% premium hard stop loss
    public static final double SL_LIMIT_BUFFER =
            1.03; // 3% buffer above trigger for limit fill guarantee

    private final ShoonyaOrderService orderService;
    private final ShoonyaOptionChainService optionChainService;
    private final TelegramService telegramService;
    private final ShoonyaConfig config;

    private ExecutionMode executionMode = ExecutionMode.PAPER;
    private final Map<String, ActiveSpreadPosition> positions = new ConcurrentHashMap<>();
    private final AtomicInteger tradeCounter = new AtomicInteger(1);

    @Autowired
    public ExecutionManager(
            ShoonyaOrderService orderService,
            ShoonyaOptionChainService optionChainService,
            TelegramService telegramService,
            @Autowired(required = false) ShoonyaConfig config) {
        this.orderService = orderService;
        this.optionChainService = optionChainService;
        this.telegramService = telegramService;
        this.config = config;
        if (config != null && config.getExecutionMode() != null) {
            this.executionMode = config.getExecutionMode();
            log.info(
                    "[EXECUTION] Execution mode initialized from .env / ShoonyaConfig: {}",
                    this.executionMode);
        }
    }

    public ExecutionManager(
            ShoonyaOrderService orderService,
            ShoonyaOptionChainService optionChainService,
            TelegramService telegramService) {
        this(orderService, optionChainService, telegramService, null);
    }

    /** Executes a Directional Short Option Spread with protective Hedge and instant SL-L order. */
    public synchronized ActiveSpreadPosition executeDirectionalOptionSelling(
            String strategyId,
            String underlying,
            String optionType, // "PE" for Bullish, "CE" for Bearish
            BigDecimal atmStrike,
            int quantity,
            boolean buyHedge) {
        String tradeId = "TRD_" + tradeCounter.getAndIncrement();
        log.info(
                "[EXECUTION] [{}] Initiating {} Short Trade on {} ATM Strike {} (Mode: {}, Hedge: {})",
                tradeId,
                optionType,
                underlying,
                atmStrike,
                executionMode,
                buyHedge);

        // 1. Resolve Contracts from Option Chain
        OptionChainResponse chain = optionChainService.getNifty50OptionChain(atmStrike, 8, true);
        String tradingSymbol = chain != null ? chain.tradingSymbol() : "NIFTY29SEP26F";

        BigDecimal hedgeStrike =
                "PE".equalsIgnoreCase(optionType)
                        ? atmStrike.subtract(BigDecimal.valueOf(hedgeStrikeOffset))
                        : atmStrike.add(BigDecimal.valueOf(hedgeStrikeOffset));

        String shortSymbol = resolveOptionSymbol(underlying, tradingSymbol, optionType, atmStrike);
        String hedgeSymbol =
                resolveOptionSymbol(underlying, tradingSymbol, optionType, hedgeStrike);

        BigDecimal shortEntryPremium =
                resolveOptionPremium(chain, atmStrike, optionType, BigDecimal.valueOf(150.00));
        BigDecimal hedgeEntryPremium =
                resolveOptionPremium(chain, hedgeStrike, optionType, BigDecimal.valueOf(5.00));

        String shortOrderId = "ORD_SHORT_" + tradeId;
        String hedgeOrderId = buyHedge ? "ORD_HEDGE_" + tradeId : "NONE";
        String slOrderId = "ORD_SLL_" + tradeId;

        // 2. Calculate Hard SL-L Trigger and Limit Price
        BigDecimal slTriggerPrice =
                shortEntryPremium
                        .multiply(BigDecimal.valueOf(SL_MULTIPLIER))
                        .setScale(2, RoundingMode.HALF_UP);
        BigDecimal slLimitPrice =
                slTriggerPrice
                        .multiply(BigDecimal.valueOf(SL_LIMIT_BUFFER))
                        .setScale(2, RoundingMode.HALF_UP);

        if (executionMode == ExecutionMode.LIVE) {
            log.info("[LIVE-ORDER] Routing orders to Shoonya broker API...");

            // Step A: Buy Protective Hedge Leg first (to secure margin benefit)
            if (buyHedge) {
                OrderRequest hedgeReq =
                        new OrderRequest(
                                hedgeSymbol,
                                "NFO",
                                TransactionType.BUY,
                                OrderType.MKT,
                                ProductType.MIS,
                                quantity,
                                BigDecimal.ZERO,
                                null,
                                "HEDGE_" + tradeId);
                OrderResponse hedgeResp = orderService.placeOrder(hedgeReq);
                if (hedgeResp != null && hedgeResp.success()) {
                    hedgeOrderId = hedgeResp.orderId();
                    log.info("[LIVE-ORDER] Hedge leg placed successfully: {}", hedgeOrderId);
                } else {
                    String err = hedgeResp != null ? hedgeResp.message() : "Unknown error";
                    log.error(
                            "[LIVE-ORDER] Hedge leg order failed: {}. Aborting trade {} to prevent unprotected short.",
                            err,
                            tradeId);
                    return null;
                }
            }

            // Step B: Sell ATM Option Leg
            OrderRequest shortReq =
                    new OrderRequest(
                            shortSymbol,
                            "NFO",
                            TransactionType.SELL,
                            OrderType.MKT,
                            ProductType.MIS,
                            quantity,
                            BigDecimal.ZERO,
                            null,
                            "SHORT_" + tradeId);
            OrderResponse shortResp = orderService.placeOrder(shortReq);
            if (shortResp != null && shortResp.success()) {
                shortOrderId = shortResp.orderId();
                log.info("[LIVE-ORDER] Short leg placed successfully: {}", shortOrderId);
            } else {
                String err = shortResp != null ? shortResp.message() : "Unknown error";
                log.error(
                        "[LIVE-ORDER] Short leg order failed: {}. Rolling back any active hedge leg for trade {}.",
                        err,
                        tradeId);
                if (buyHedge && !hedgeOrderId.equals("NONE") && !hedgeOrderId.startsWith("ORD_")) {
                    OrderRequest rollbackHedgeReq =
                            new OrderRequest(
                                    hedgeSymbol,
                                    "NFO",
                                    TransactionType.SELL,
                                    OrderType.MKT,
                                    ProductType.MIS,
                                    quantity,
                                    BigDecimal.ZERO,
                                    null,
                                    "ROLLBACK_HEDGE_" + tradeId);
                    OrderResponse rollbackResp = orderService.placeOrder(rollbackHedgeReq);
                    log.info(
                            "[LIVE-ORDER] Hedge rollback result for {}: {}",
                            tradeId,
                            rollbackResp != null ? rollbackResp.message() : "null");
                }
                return null;
            }

            // Step C: Place Instant Broker-Level SL-L Order for the Short Option Leg
            OrderRequest slReq =
                    new OrderRequest(
                            shortSymbol,
                            "NFO",
                            TransactionType.BUY,
                            OrderType.SL_LMT,
                            ProductType.MIS,
                            quantity,
                            slLimitPrice,
                            slTriggerPrice,
                            "SL_LMT_" + tradeId);
            OrderResponse slResp = orderService.placeOrder(slReq);
            if (slResp != null && slResp.success()) {
                slOrderId = slResp.orderId();
                log.info(
                        "[LIVE-SL-L] Broker SL-L Order Placed! OrderId: {} | Trigger: {} | Limit: {}",
                        slOrderId,
                        slTriggerPrice,
                        slLimitPrice);
            } else {
                log.warn(
                        "[LIVE-SL-L] SL-L Order placement failed on attempt 1. Retrying once...");
                slResp = orderService.placeOrder(slReq);
                if (slResp != null && slResp.success()) {
                    slOrderId = slResp.orderId();
                    log.info(
                            "[LIVE-SL-L] Broker SL-L Order Placed on retry! OrderId: {}",
                            slOrderId);
                } else {
                    log.error(
                            "[LIVE-SL-L] CRITICAL ALERT: SL-L order placement failed on retry: {}. Position {} is UNPROTECTED at broker level!",
                            slResp != null ? slResp.message() : "null",
                            tradeId);
                }
            }

        } else {
            log.info(
                    "[PAPER-ORDER] Virtual spread position created: Short {} @ {} | Hedge {} @ {} | SL-L Trigger: {}",
                    shortSymbol,
                    shortEntryPremium,
                    buyHedge ? hedgeSymbol : "NONE",
                    hedgeEntryPremium,
                    slTriggerPrice);
        }

        ActiveSpreadPosition pos =
                ActiveSpreadPosition.open(
                        tradeId,
                        strategyId,
                        underlying,
                        optionType,
                        atmStrike,
                        shortSymbol,
                        shortOrderId,
                        shortEntryPremium,
                        buyHedge ? hedgeSymbol : null,
                        hedgeOrderId,
                        buyHedge ? hedgeEntryPremium : BigDecimal.ZERO,
                        slOrderId,
                        slTriggerPrice,
                        slLimitPrice,
                        quantity,
                        executionMode);

        positions.put(tradeId, pos);

        // Dispatch Telegram Alert for both PAPER and LIVE modes
        if (telegramService != null) {
            telegramService.sendTradeAlert(pos, strategyId);
        }

        return pos;
    }

    /** Closes an active spread position (cancels broker SL-L order and executes exit orders). */
    public synchronized ActiveSpreadPosition closeSpreadPosition(
            String tradeId, String exitReason) {
        ActiveSpreadPosition pos = positions.get(tradeId);
        if (pos == null || pos.isClosed()) {
            return pos;
        }

        log.info(
                "[EXECUTION] [{}] Closing spread position {}. Reason: {}",
                tradeId,
                pos.shortSymbol(),
                exitReason);

        BigDecimal shortExitPremium =
                pos.shortEntryPremium()
                        .multiply(BigDecimal.valueOf(0.80))
                        .setScale(2, RoundingMode.HALF_UP);
        BigDecimal hedgeExitPremium =
                pos.hedgeEntryPremium() != null
                        ? pos.hedgeEntryPremium()
                                .multiply(BigDecimal.valueOf(0.50))
                                .setScale(2, RoundingMode.HALF_UP)
                        : BigDecimal.ZERO;

        if (executionMode == ExecutionMode.LIVE) {
            // 1. Cancel Open Broker SL-L Order
            if (pos.slOrderId() != null && !pos.slOrderId().startsWith("ORD_")) {
                OrderResponse cancelResp = orderService.cancelOrder(pos.slOrderId());
                log.info(
                        "[LIVE-CANCEL] Cancelled open SL-L order {}: {}",
                        pos.slOrderId(),
                        cancelResp != null ? cancelResp.message() : "done");
            }

            // 2. Buy back Short Option Leg
            OrderRequest closeShortReq =
                    new OrderRequest(
                            pos.shortSymbol(),
                            "NFO",
                            TransactionType.BUY,
                            OrderType.MKT,
                            ProductType.MIS,
                            pos.quantity(),
                            BigDecimal.ZERO,
                            null,
                            "EXIT_SHORT_" + tradeId);
            OrderResponse closeShortResp = orderService.placeOrder(closeShortReq);
            if (closeShortResp == null || !closeShortResp.success()) {
                log.error(
                        "[LIVE-EXIT] CRITICAL: Failed to buy back short leg {} for trade {}: {}",
                        pos.shortSymbol(),
                        tradeId,
                        closeShortResp != null ? closeShortResp.message() : "null");
            }

            // 3. Sell Hedge Option Leg (if active)
            if (pos.hedgeSymbol() != null) {
                OrderRequest closeHedgeReq =
                        new OrderRequest(
                                pos.hedgeSymbol(),
                                "NFO",
                                TransactionType.SELL,
                                OrderType.MKT,
                                ProductType.MIS,
                                pos.quantity(),
                                BigDecimal.ZERO,
                                null,
                                "EXIT_HEDGE_" + tradeId);
                OrderResponse closeHedgeResp = orderService.placeOrder(closeHedgeReq);
                if (closeHedgeResp == null || !closeHedgeResp.success()) {
                    log.error(
                            "[LIVE-EXIT] Failed to sell hedge leg {} for trade {}: {}",
                            pos.hedgeSymbol(),
                            tradeId,
                            closeHedgeResp != null ? closeHedgeResp.message() : "null");
                }
            }
        }

        // Calculate Realized PnL: (Short Entry - Short Exit) + (Hedge Exit - Hedge Entry)
        BigDecimal shortPnl =
                pos.shortEntryPremium()
                        .subtract(shortExitPremium)
                        .multiply(BigDecimal.valueOf(pos.quantity()));
        BigDecimal hedgePnl =
                pos.hedgeSymbol() != null
                        ? hedgeExitPremium
                                .subtract(pos.hedgeEntryPremium())
                                .multiply(BigDecimal.valueOf(pos.quantity()))
                        : BigDecimal.ZERO;
        BigDecimal realizedPnl = shortPnl.add(hedgePnl).setScale(2, RoundingMode.HALF_UP);

        ActiveSpreadPosition closed =
                pos.close(
                        Instant.now(), shortExitPremium, hedgeExitPremium, realizedPnl, exitReason);
        positions.put(tradeId, closed);
        log.info("[EXECUTION] [{}] Position Closed. Realized PnL: Rs. {}", tradeId, realizedPnl);

        // Dispatch Telegram Exit Alert for both PAPER and LIVE modes
        if (telegramService != null) {
            telegramService.sendTradeExitAlert(closed, pos.strategyId(), exitReason);
        }

        return closed;
    }

    private String resolveOptionSymbol(
            String underlying, String futSymbol, String optionType, BigDecimal strike) {
        String expiryPart = futSymbol.replaceAll("[^0-9A-Za-z]", "");
        if (expiryPart.endsWith("F")) {
            expiryPart = expiryPart.substring(0, expiryPart.length() - 1);
        }
        return expiryPart + optionType.toUpperCase() + strike.intValue();
    }

    private BigDecimal resolveOptionPremium(
            OptionChainResponse chain, BigDecimal strike, String optionType, BigDecimal fallback) {
        if (chain != null && chain.strikes() != null) {
            for (OptionStrike s : chain.strikes()) {
                if (s.strikePrice().compareTo(strike) == 0) {
                    OptionContract contract =
                            "PE".equalsIgnoreCase(optionType) ? s.put() : s.call();
                    if (contract != null
                            && contract.ltp() != null
                            && contract.ltp().compareTo(BigDecimal.ZERO) > 0) {
                        return contract.ltp();
                    }
                }
            }
        }
        return fallback;
    }

    public ExecutionMode getExecutionMode() {
        return executionMode;
    }

    public void setExecutionMode(ExecutionMode mode) {
        this.executionMode = mode;
        log.info("[EXECUTION] Mode switched to: {}", mode);
    }

    public List<ActiveSpreadPosition> getOpenPositions() {
        return positions.values().stream().filter(p -> !p.isClosed()).toList();
    }

    public List<ActiveSpreadPosition> getAllPositions() {
        return new ArrayList<>(positions.values());
    }

    public ActiveSpreadPosition getPosition(String tradeId) {
        return positions.get(tradeId);
    }

    public int getHedgeStrikeOffset() {
        return hedgeStrikeOffset;
    }

    public void setHedgeStrikeOffset(int hedgeStrikeOffset) {
        this.hedgeStrikeOffset = hedgeStrikeOffset;
    }
}
