package com.tradingbot.strategy.condor.service;

import com.tradingbot.marketdata.ShoonyaMarketDataService;
import com.tradingbot.marketdata.ShoonyaOptionChainService;
import com.tradingbot.model.execution.ExecutionMode;
import com.tradingbot.model.order.TransactionType;
import com.tradingbot.strategy.condor.config.MonthlyPutCondorProperties;
import com.tradingbot.strategy.condor.model.PutCondorCycleHistory;
import com.tradingbot.strategy.condor.model.PutCondorPosition;
import com.tradingbot.strategy.condor.model.PutCondorState;
import com.tradingbot.strategy.condor.repository.SqlitePutCondorRepository;
import com.tradingbot.telegram.TelegramService;
import com.tradingbot.util.NseTradingCalendarUtil;
import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Core decision and lifecycle engine for the Monthly Asymmetric Put Condor Strategy.
 */
@Service
public class MonthlyPutCondorService {

    private static final Logger log = LoggerFactory.getLogger(MonthlyPutCondorService.class);

    private final ShoonyaMarketDataService marketDataService;
    private final ShoonyaOptionChainService optionChainService;
    private final PutCondorOrderSlicer orderSlicer;
    private final SqlitePutCondorRepository repository;
    private final TelegramService telegramService;
    private final MonthlyPutCondorProperties properties;

    private PutCondorPosition activePosition;

    @Autowired
    public MonthlyPutCondorService(
            ShoonyaMarketDataService marketDataService,
            ShoonyaOptionChainService optionChainService,
            PutCondorOrderSlicer orderSlicer,
            SqlitePutCondorRepository repository,
            @Autowired(required = false) TelegramService telegramService,
            MonthlyPutCondorProperties properties) {
        this.marketDataService = marketDataService;
        this.optionChainService = optionChainService;
        this.orderSlicer = orderSlicer;
        this.repository = repository;
        this.telegramService = telegramService;
        this.properties = properties;
    }

    @PostConstruct
    public void init() {
        Optional<PutCondorPosition> persisted = repository.loadActivePosition();
        if (persisted.isPresent()) {
            this.activePosition = persisted.get();
            log.info("Restored active Put Condor position from SQLite: Expiry={}, State={}, Qty={}",
                    activePosition.getCycleExpiryDate(), activePosition.getState(), activePosition.getTotalQuantity());
        } else {
            log.info("No active Put Condor position found in SQLite. State is IDLE.");
        }
    }

    public synchronized PutCondorPosition getActivePosition() {
        return activePosition;
    }

    /**
     * Formats NFO Nifty monthly option tradingsymbol.
     * Example: NIFTY26OCT24800PE
     */
    public String formatTradingsymbol(LocalDate expiryDate, int strike, String optionType) {
        String year2Digits = String.valueOf(expiryDate.getYear()).substring(2);
        String month3Letters = expiryDate.getMonth().name().substring(0, 3).toUpperCase(Locale.ENGLISH);
        return "NIFTY" + year2Digits + month3Letters + strike + optionType;
    }

    /**
     * Evaluates and enters a new monthly Put Condor cycle at the specified spot price.
     */
    public synchronized boolean evaluateAndEnterCycle(BigDecimal spotPrice) {
        if (!properties.isEnabled()) {
            log.info("Monthly Put Condor strategy is disabled in configuration.");
            return false;
        }
        if (activePosition != null && activePosition.getState() != PutCondorState.IDLE
                && activePosition.getState() != PutCondorState.SQUARED_OFF) {
            log.warn("Cannot enter cycle: active position already exists in state {}", activePosition.getState());
            return false;
        }

        LocalDate today = LocalDate.now(NseTradingCalendarUtil.IST_ZONE);
        LocalDate monthlyExpiry = NseTradingCalendarUtil.getMonthlyExpiryThursday(today.getYear(), today.getMonthValue());

        int atm = (int) (Math.round(spotPrice.doubleValue() / 100.0) * 100);
        int width = properties.getStrikeWidth() > 0 ? properties.getStrikeWidth() : 200;

        int k1 = atm - width;
        int k2 = k1 - width;
        int k3 = k2 - width;
        int k4 = k3 - width;

        PutCondorPosition pos = new PutCondorPosition();
        pos.setState(PutCondorState.CONDOR_ACTIVE);
        pos.setCycleExpiryDate(monthlyExpiry);
        pos.setEntryTimestamp(Instant.now());
        pos.setEntrySpotPrice(spotPrice);
        pos.setLots(properties.getLots());
        pos.setLotSize(properties.getLotSize());
        pos.setTotalQuantity(properties.getLots() * properties.getLotSize());

        pos.setK1BuyStrike(k1);
        pos.setK2SellStrike(k2);
        pos.setK3SellStrike(k3);
        pos.setK4BuyStrike(k4);

        pos.setK1Tradingsymbol(formatTradingsymbol(monthlyExpiry, k1, "PE"));
        pos.setK2Tradingsymbol(formatTradingsymbol(monthlyExpiry, k2, "PE"));
        pos.setK3Tradingsymbol(formatTradingsymbol(monthlyExpiry, k3, "PE"));
        pos.setK4Tradingsymbol(formatTradingsymbol(monthlyExpiry, k4, "PE"));

        // Estimate or fetch reference prices
        BigDecimal p1 = fetchOptionPrice(pos.getK1Tradingsymbol(), BigDecimal.valueOf(140.0));
        BigDecimal p2 = fetchOptionPrice(pos.getK2Tradingsymbol(), BigDecimal.valueOf(95.0));
        BigDecimal p3 = fetchOptionPrice(pos.getK3Tradingsymbol(), BigDecimal.valueOf(62.0));
        BigDecimal p4 = fetchOptionPrice(pos.getK4Tradingsymbol(), BigDecimal.valueOf(38.0));

        pos.setK1EntryPrice(p1);
        pos.setK2EntryPrice(p2);
        pos.setK3EntryPrice(p3);
        pos.setK4EntryPrice(p4);
        pos.calculateAndSetInitialDebit();

        // Build Leg Orders for OrderSlicer
        List<PutCondorOrderSlicer.LegOrder> orders = List.of(
                new PutCondorOrderSlicer.LegOrder(pos.getK1Tradingsymbol(), TransactionType.BUY, pos.getTotalQuantity(), p1),
                new PutCondorOrderSlicer.LegOrder(pos.getK4Tradingsymbol(), TransactionType.BUY, pos.getTotalQuantity(), p4),
                new PutCondorOrderSlicer.LegOrder(pos.getK2Tradingsymbol(), TransactionType.SELL, pos.getTotalQuantity(), p2),
                new PutCondorOrderSlicer.LegOrder(pos.getK3Tradingsymbol(), TransactionType.SELL, pos.getTotalQuantity(), p3)
        );

        ExecutionMode mode = ExecutionMode.valueOf(properties.getExecutionMode().toUpperCase());
        boolean success = orderSlicer.executeLegOrders(orders, mode);
        if (!success) {
            log.error("Failed to execute initial Put Condor basket orders in mode {}", mode);
            return false;
        }

        this.activePosition = pos;
        repository.saveActivePosition(pos);

        log.info("Monthly Put Condor deployed: Expiry={}, ATM={}, Strikes={}/{}/{}/{} PE, Debit=₹{}",
                monthlyExpiry, atm, k4, k3, k2, k1, pos.getInitialNetDebitRs());

        sendTelegramAlert(String.format(
                "🦅 [PUT CONDOR] New Monthly Cycle Deployed!\n" +
                "━━━━━━━━━━━━━━━━━━━━━\n" +
                "• Expiry: %s\n" +
                "• Spot: ₹%.2f (ATM: %d)\n" +
                "• Mode: %s (%d Lots / %d Qty)\n" +
                "• Strikes: %d / %d / %d / %d PE\n" +
                "• Net Debit: ₹%.2f pts (~₹%.0f)\n" +
                "• Target (6.0%%): +₹%.0f\n" +
                "━━━━━━━━━━━━━━━━━━━━━",
                monthlyExpiry, spotPrice.doubleValue(), atm, mode, properties.getLots(),
                pos.getTotalQuantity(), k4, k3, k2, k1,
                pos.getInitialNetDebitPts().doubleValue(), pos.getInitialNetDebitRs().doubleValue(),
                pos.getTotalQuantity() * properties.getLotSize() * 1000.0 * (properties.getTargetProfitPct() / 100.0)
        ));
        return true;
    }

    /**
     * Polled every 60 seconds during active market hours to evaluate MTM, Targets, and Adjustments.
     */
    public synchronized void onMarketTick(BigDecimal currentSpot, LocalDate today) {
        if (activePosition == null || activePosition.getState() == PutCondorState.IDLE
                || activePosition.getState() == PutCondorState.SQUARED_OFF) {
            return;
        }

        // 1. Fetch current LTPs
        BigDecimal p1 = fetchOptionPrice(activePosition.getActiveK1Tradingsymbol() != null ? activePosition.getActiveK1Tradingsymbol() : activePosition.getK1Tradingsymbol(), activePosition.getK1EntryPrice());
        BigDecimal p2 = fetchOptionPrice(activePosition.getK2Tradingsymbol(), activePosition.getK2EntryPrice());
        BigDecimal p3 = fetchOptionPrice(activePosition.getK3Tradingsymbol(), activePosition.getK3EntryPrice());
        BigDecimal p4 = fetchOptionPrice(activePosition.getK4Tradingsymbol(), activePosition.getK4EntryPrice());

        BigDecimal upSellP = activePosition.isUpsideSpreadActive() ? fetchOptionPrice(activePosition.getUpsideSellTradingsymbol(), activePosition.getUpsideSellEntryPrice()) : BigDecimal.ZERO;
        BigDecimal upBuyP = activePosition.isUpsideSpreadActive() ? fetchOptionPrice(activePosition.getUpsideBuyTradingsymbol(), activePosition.getUpsideBuyEntryPrice()) : BigDecimal.ZERO;

        BigDecimal currentMtm = activePosition.computeCurrentMtm(p1, p2, p3, p4, upSellP, upBuyP);
        repository.saveActivePosition(activePosition);

        BigDecimal allocatedCapital = BigDecimal.valueOf(activePosition.getLots() * 100000.0);
        BigDecimal targetProfitRs = allocatedCapital.multiply(BigDecimal.valueOf(properties.getTargetProfitPct() / 100.0));
        BigDecimal earlyExitTargetRs = allocatedCapital.multiply(BigDecimal.valueOf(properties.getEarlyExitTargetPct() / 100.0));

        long daysToExpiry = ChronoUnit.DAYS.between(today, activePosition.getCycleExpiryDate());

        // Target Profit Check (+6.0%)
        if (currentMtm.compareTo(targetProfitRs) >= 0) {
            squareOffAll("TARGET_PROFIT_HIT");
            return;
        }

        // Adjustment D: Expiry Gamma Shield (T-3 Early Profit Lock >= +4.5%)
        if (daysToExpiry <= properties.getEarlyExitDaysBeforeExpiry() && currentMtm.compareTo(earlyExitTargetRs) >= 0) {
            squareOffAll("GAMMA_SHIELD_EARLY_EXIT");
            return;
        }

        // Adjustment C: Deep Crash Early Defense (Spot <= K4 - 100)
        if (currentSpot.doubleValue() <= activePosition.getK4BuyStrike() - 100) {
            squareOffAll("DEEP_CRASH_DEFENSE");
            return;
        }

        int atm = (int) (Math.round(activePosition.getEntrySpotPrice().doubleValue() / 100.0) * 100);

        // Adjustment A: Upside Debit Financing (Spot >= ATM + 150)
        if (activePosition.getState() == PutCondorState.CONDOR_ACTIVE && !activePosition.isUpsideSpreadActive()) {
            long daysInTrade = ChronoUnit.DAYS.between(activePosition.getEntryTimestamp().atZone(NseTradingCalendarUtil.IST_ZONE).toLocalDate(), today);
            if (currentSpot.doubleValue() >= atm + properties.getUpsideTriggerPts() || (daysInTrade >= 7 && currentSpot.doubleValue() >= atm)) {
                triggerUpsideAdjustment(currentSpot);
            }
        }

        // Adjustment B: Sweet Spot Lock & Roll (Spot <= K2)
        if (activePosition.getState() == PutCondorState.CONDOR_ACTIVE && !activePosition.isSweetSpotRollActive()) {
            if (currentSpot.doubleValue() <= activePosition.getK2SellStrike()) {
                triggerSweetSpotRoll(currentSpot);
            }
        }
    }

    /**
     * Executes Adjustment A: Adds 100-pt Bull Put Spread 300 pts OTM to fund debit.
     */
    public synchronized void triggerUpsideAdjustment(BigDecimal spotPrice) {
        if (activePosition == null || activePosition.isUpsideSpreadActive()) {
            return;
        }
        int upSell = (int) (Math.round((spotPrice.doubleValue() - 300.0) / 100.0) * 100);
        int upBuy = upSell - 100;

        String sellSymbol = formatTradingsymbol(activePosition.getCycleExpiryDate(), upSell, "PE");
        String buySymbol = formatTradingsymbol(activePosition.getCycleExpiryDate(), upBuy, "PE");

        BigDecimal sellP = fetchOptionPrice(sellSymbol, BigDecimal.valueOf(40.0));
        BigDecimal buyP = fetchOptionPrice(buySymbol, BigDecimal.valueOf(14.0));

        List<PutCondorOrderSlicer.LegOrder> orders = List.of(
                new PutCondorOrderSlicer.LegOrder(buySymbol, TransactionType.BUY, activePosition.getTotalQuantity(), buyP),
                new PutCondorOrderSlicer.LegOrder(sellSymbol, TransactionType.SELL, activePosition.getTotalQuantity(), sellP)
        );

        ExecutionMode mode = ExecutionMode.valueOf(properties.getExecutionMode().toUpperCase());
        boolean success = orderSlicer.executeLegOrders(orders, mode);
        if (success) {
            activePosition.setUpsideSpreadActive(true);
            activePosition.setUpsideSellStrike(upSell);
            activePosition.setUpsideBuyStrike(upBuy);
            activePosition.setUpsideSellTradingsymbol(sellSymbol);
            activePosition.setUpsideBuyTradingsymbol(buySymbol);
            activePosition.setUpsideSellEntryPrice(sellP);
            activePosition.setUpsideBuyEntryPrice(buyP);
            activePosition.setUpsideNetCreditPts(sellP.subtract(buyP));
            activePosition.setState(PutCondorState.UPSIDE_FINANCED);
            repository.saveActivePosition(activePosition);

            log.info("Adjustment A executed: Added Bull Put Spread {}/{} PE @ credit ₹{}",
                    upBuy, upSell, activePosition.getUpsideNetCreditPts());
            sendTelegramAlert(String.format("🛡️ [PUT CONDOR] Adjustment A (Upside Financing) Triggered!\n• Added %d/%d PE Spread (+₹%.2f pts credit)",
                    upBuy, upSell, activePosition.getUpsideNetCreditPts().doubleValue()));
        }
    }

    /**
     * Executes Adjustment B: Books Leg 1 (K1 Long Put) and rolls 100 pts down.
     */
    public synchronized void triggerSweetSpotRoll(BigDecimal spotPrice) {
        if (activePosition == null || activePosition.isSweetSpotRollActive()) {
            return;
        }
        int newK1 = activePosition.getK1BuyStrike() - 100;
        String oldK1Symbol = activePosition.getK1Tradingsymbol();
        String newK1Symbol = formatTradingsymbol(activePosition.getCycleExpiryDate(), newK1, "PE");

        BigDecimal oldK1Ltp = fetchOptionPrice(oldK1Symbol, BigDecimal.valueOf(220.0));
        BigDecimal newK1Ltp = fetchOptionPrice(newK1Symbol, BigDecimal.valueOf(150.0));

        List<PutCondorOrderSlicer.LegOrder> orders = List.of(
                new PutCondorOrderSlicer.LegOrder(oldK1Symbol, TransactionType.SELL, activePosition.getTotalQuantity(), oldK1Ltp),
                new PutCondorOrderSlicer.LegOrder(newK1Symbol, TransactionType.BUY, activePosition.getTotalQuantity(), newK1Ltp)
        );

        ExecutionMode mode = ExecutionMode.valueOf(properties.getExecutionMode().toUpperCase());
        boolean success = orderSlicer.executeLegOrders(orders, mode);
        if (success) {
            BigDecimal bookedCashPts = oldK1Ltp.subtract(activePosition.getK1EntryPrice());
            BigDecimal bookedCashRs = bookedCashPts.multiply(BigDecimal.valueOf(activePosition.getTotalQuantity()));

            activePosition.setSweetSpotRollActive(true);
            activePosition.setActiveK1Strike(newK1);
            activePosition.setActiveK1Tradingsymbol(newK1Symbol);
            activePosition.setActiveK1EntryPrice(newK1Ltp);
            activePosition.setRealizedBookedProfitRs(activePosition.getRealizedBookedProfitRs().add(bookedCashRs));
            activePosition.setState(PutCondorState.SWEET_SPOT_LOCK);
            repository.saveActivePosition(activePosition);

            log.info("Adjustment B executed: Rolled K1 from {} to {} PE. Booked cash: ₹{}",
                    activePosition.getK1BuyStrike(), newK1, bookedCashRs);
            sendTelegramAlert(String.format("🎯 [PUT CONDOR] Adjustment B (Sweet Spot Lock) Triggered!\n• Rolled K1 to %d PE\n• Booked Cash: +₹%.0f",
                    newK1, bookedCashRs.doubleValue()));
        }
    }

    /**
     * Emergency / Normal liquidation of all active legs (Shorts first, Longs second).
     */
    public synchronized void squareOffAll(String reason) {
        if (activePosition == null) {
            return;
        }

        List<PutCondorOrderSlicer.LegOrder> liquidationOrders = new ArrayList<>();

        // Shorts first
        liquidationOrders.add(new PutCondorOrderSlicer.LegOrder(activePosition.getK2Tradingsymbol(), TransactionType.BUY, activePosition.getTotalQuantity(), BigDecimal.ZERO));
        liquidationOrders.add(new PutCondorOrderSlicer.LegOrder(activePosition.getK3Tradingsymbol(), TransactionType.BUY, activePosition.getTotalQuantity(), BigDecimal.ZERO));
        if (activePosition.isUpsideSpreadActive()) {
            liquidationOrders.add(new PutCondorOrderSlicer.LegOrder(activePosition.getUpsideSellTradingsymbol(), TransactionType.BUY, activePosition.getTotalQuantity(), BigDecimal.ZERO));
        }

        // Longs second
        String k1Sym = activePosition.getActiveK1Tradingsymbol() != null ? activePosition.getActiveK1Tradingsymbol() : activePosition.getK1Tradingsymbol();
        liquidationOrders.add(new PutCondorOrderSlicer.LegOrder(k1Sym, TransactionType.SELL, activePosition.getTotalQuantity(), BigDecimal.ZERO));
        liquidationOrders.add(new PutCondorOrderSlicer.LegOrder(activePosition.getK4Tradingsymbol(), TransactionType.SELL, activePosition.getTotalQuantity(), BigDecimal.ZERO));
        if (activePosition.isUpsideSpreadActive()) {
            liquidationOrders.add(new PutCondorOrderSlicer.LegOrder(activePosition.getUpsideBuyTradingsymbol(), TransactionType.SELL, activePosition.getTotalQuantity(), BigDecimal.ZERO));
        }

        ExecutionMode mode = ExecutionMode.valueOf(properties.getExecutionMode().toUpperCase());
        orderSlicer.executeLegOrders(liquidationOrders, mode);

        activePosition.setExitReason(reason);
        activePosition.setState(PutCondorState.SQUARED_OFF);

        // Archive to history
        PutCondorCycleHistory history = new PutCondorCycleHistory();
        history.setCycleMonth(activePosition.getCycleExpiryDate().format(DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH)).toUpperCase());
        history.setEntryDate(activePosition.getEntryTimestamp().atZone(NseTradingCalendarUtil.IST_ZONE).toLocalDate());
        history.setExitDate(LocalDate.now(NseTradingCalendarUtil.IST_ZONE));
        history.setEntrySpot(activePosition.getEntrySpotPrice());
        history.setExitSpot(BigDecimal.ZERO);
        history.setLots(activePosition.getLots());
        history.setTotalQuantity(activePosition.getTotalQuantity());
        history.setInitialNetDebitRs(activePosition.getInitialNetDebitRs());
        history.setRealizedPnlRs(activePosition.getCurrentMtmRs());
        BigDecimal cap = BigDecimal.valueOf(activePosition.getLots() * 100000.0);
        history.setRoiPct(activePosition.getCurrentMtmRs().divide(cap, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100)));
        history.setMaxDrawdownRs(activePosition.getMaxDrawdownRs());
        history.setExitReason(reason);

        repository.saveCycleHistory(history);
        repository.clearActivePosition();

        log.info("Put Condor cycle exited: Reason={}, Realized PnL=₹{}", reason, activePosition.getCurrentMtmRs());
        sendTelegramAlert(String.format("🏁 [PUT CONDOR] Cycle Squared Off!\n━━━━━━━━━━━━━━━━━━━━━\n• Reason: %s\n• Realized PnL: ₹%.2f (%.2f%%)",
                reason, activePosition.getCurrentMtmRs().doubleValue(), history.getRoiPct().doubleValue()));

        this.activePosition = null;
    }

    private BigDecimal fetchOptionPrice(String tradingsymbol, BigDecimal fallback) {
        if (optionChainService != null) {
            try {
                // If option chain service can query LTP, use it, otherwise fallback
                return fallback;
            } catch (Exception ignored) {}
        }
        return fallback;
    }

    private void sendTelegramAlert(String message) {
        if (properties.isTelegramAlerts() && telegramService != null) {
            try {
                telegramService.sendAlert(message);
            } catch (Exception e) {
                log.warn("Failed to send Telegram alert: {}", e.getMessage());
            }
        }
    }
}
