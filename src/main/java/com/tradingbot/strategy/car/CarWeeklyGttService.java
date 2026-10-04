package com.tradingbot.strategy.car;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingbot.marketdata.HistoricalOhlcCacheService;
import com.tradingbot.model.Candle;
import com.tradingbot.strategy.car.config.CarExecutionGate;
import com.tradingbot.strategy.car.config.CarWeeklyProperties;
import com.tradingbot.strategy.car.gtt.GttExecutionGateway;
import com.tradingbot.strategy.car.gtt.GttFill;
import com.tradingbot.strategy.car.gtt.LiveGttTrigger;
import com.tradingbot.strategy.car.model.*;
import com.tradingbot.strategy.car.universe.Nifty100Registry;
import com.tradingbot.telegram.TelegramService;
import jakarta.annotation.PostConstruct;
import java.io.File;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class CarWeeklyGttService {

    private static final Logger log = LoggerFactory.getLogger(CarWeeklyGttService.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final String SELL_KEY_SUFFIX = ":SELL_TARGET";
    private static final String PAPER_BROKER = "SHOONYA";

    private final CarWeeklyProperties properties;
    private final CarCalculator calculator;
    private final CarWeeklyTriggerGenerator triggerGenerator;
    private final HistoricalOhlcCacheService ohlcService;
    private final List<GttExecutionGateway> gttGateways;
    private final TelegramService telegramService;
    private final CarExecutionGate executionGate;
    private final ObjectMapper objectMapper =
            new ObjectMapper()
                    .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
                    .disable(
                            com.fasterxml.jackson.databind.SerializationFeature
                                    .WRITE_DATES_AS_TIMESTAMPS);

    private CarPortfolioState portfolioState;

    @Autowired
    public CarWeeklyGttService(
            CarWeeklyProperties properties,
            CarCalculator calculator,
            CarWeeklyTriggerGenerator triggerGenerator,
            HistoricalOhlcCacheService ohlcService,
            List<GttExecutionGateway> gttGateways,
            @Autowired(required = false) TelegramService telegramService,
            @Autowired(required = false) CarExecutionGate executionGate) {
        this.properties = properties;
        this.calculator = calculator;
        this.triggerGenerator = triggerGenerator;
        this.ohlcService = ohlcService;
        this.gttGateways = gttGateways != null ? gttGateways : List.of();
        this.telegramService = telegramService;
        this.executionGate = executionGate;
        this.portfolioState =
                new CarPortfolioState(
                        BigDecimal.valueOf(properties.getTotalCapital()),
                        properties.getNumParts(),
                        BigDecimal.valueOf(properties.getProfitTargetPct()));
    }

    @PostConstruct
    public void init() {
        loadState();
    }

    /** Runs the weekly routine, skipping it when this trading week is already reconciled. */
    public synchronized void runSundayWeeklyRoutine() {
        runSundayWeeklyRoutine(false);
    }

    /**
     * Runs the weekly routine.
     *
     * @param force when true, reruns even if this ISO week has already been reconciled
     */
    public synchronized void runSundayWeeklyRoutine(boolean force) {
        if (!properties.isEnabled()) {
            log.info("[CAR-WEEKLY] Strategy is disabled in configuration.");
            return;
        }

        LocalDate weekStart = CarWeekCalendar.activeWeekStart();
        if (!force && weekStart != null && weekStart.equals(portfolioState.getLastRunWeek())) {
            log.info(
                    "[CAR-WEEKLY] Weekly routine already completed for week starting {}. Skipping"
                            + " duplicate run (startup + cron + manual triggers are idempotent).",
                    weekStart);
            return;
        }

        GttExecutionGateway gateway = resolveGateway();
        if (gateway == null) {
            return;
        }

        log.info("==================================================================");
        log.info("       CAR WEEKLY GTT STRATEGY - SUNDAY RECONCILIATION            ");
        log.info("==================================================================");

        BigDecimal unitSize = portfolioState.getUnitSize();
        log.info(
                "[CAR-WEEKLY] Week starting {} | Gateway: {} | Total Capital: ₹{} | UNIT Size:"
                        + " ₹{} | Available Units: {}/{}",
                weekStart,
                gateway.getBrokerName(),
                portfolioState.getTotalCapital(),
                unitSize,
                portfolioState.getAvailableUnits(),
                properties.getNumParts());

        // 1. Sync live broker Demat holdings (read-only, from every registered gateway)
        Set<String> allHeldSymbols = new HashSet<>(portfolioState.getHoldings().keySet());
        syncDematHoldings(allHeldSymbols);

        // 2. Detect triggers that fired during the week -> book fills, release capital
        reconcileFillEvents(gateway);

        // 3. Cancel our own duplicate triggers and re-adopt any trigger whose id we lost
        syncLiveTriggers(gateway);

        // 4. Scan the universe for CAR-positive candidates
        ScanResult scan = scanUniverse(allHeldSymbols);
        Set<String> positiveSymbols = scan.positiveSymbols();

        // 5. Spec 3.1: cancel pending buys for symbols that were evaluated and are no longer
        //    CAR-positive, so stale triggers are never left hanging in the market.
        cancelBuyGttsForNonPositive(gateway, scan.evaluatedSymbols(), positiveSymbols);

        // 6. Manage Sell Targets for Holdings (Skipping Accumulation-Only / SGB symbols)
        manageSellTargetGtts(gateway, weekStart);

        // 7. Place / Modify Buy GTTs for CAR-Positive stocks up to available capital units
        reconcileBuyGtts(scan.results(), gateway, weekStart);

        portfolioState.setLastRunWeek(weekStart);
        saveState();
        sendSundayTelegramReport(scan.results());
    }

    // ------------------------------------------------------------------ gateway

    /**
     * Resolves the single gateway this routine is allowed to talk to.
     *
     * <p>In PAPER mode every call is routed to the local watcher, so an unauthenticated {@code POST
     * /api/v1/car/run-weekly} or a default configuration can never reach a real broker. In LIVE
     * mode exactly one configured gateway is used - dispatching to every registered gateway would
     * duplicate the exposure when a trigger fires and would let whichever gateway ran last
     * overwrite the stored trigger id.
     */
    private GttExecutionGateway resolveGateway() {
        boolean live = executionGate != null && executionGate.isLiveWithLogging();
        String wanted = live ? properties.getExecutionBroker() : PAPER_BROKER;

        for (GttExecutionGateway gw : gttGateways) {
            if (gw != null && wanted.equalsIgnoreCase(gw.getBrokerName())) {
                return gw;
            }
        }

        log.error(
                "[CAR-WEEKLY] No GTT gateway registered for broker '{}' (registered: {}). Failing"
                        + " closed - no orders will be placed.",
                wanted,
                brokerNames());
        alert("[CAR] FATAL: no GTT gateway for broker '" + wanted + "'. CAR orders are halted.");
        return null;
    }

    private List<String> brokerNames() {
        List<String> names = new ArrayList<>();
        for (GttExecutionGateway gw : gttGateways) {
            if (gw != null) {
                names.add(gw.getBrokerName());
            }
        }
        return names;
    }

    // ------------------------------------------------------------------ step 1

    private void syncDematHoldings(Set<String> allHeldSymbols) {
        if (!properties.isSyncDematHoldings()) {
            return;
        }
        for (GttExecutionGateway gw : gttGateways) {
            if (gw == null) continue;
            try {
                List<com.tradingbot.model.execution.BrokerPosition> brokerHoldings =
                        gw.getHoldings();
                if (brokerHoldings == null) continue;
                for (com.tradingbot.model.execution.BrokerPosition h : brokerHoldings) {
                    String sym = h.symbol();
                    allHeldSymbols.add(sym);
                    if (!properties.isAccumulationOnly(sym)
                            && !portfolioState.getHoldings().containsKey(sym)) {
                        log.info(
                                "[CAR-WEEKLY] Discovered live {} Demat holding for {}: {} shares @"
                                        + " avg ₹{}",
                                gw.getBrokerName(),
                                sym,
                                h.quantity(),
                                h.averagePrice());
                        portfolioState.addFill(sym, (int) h.quantity(), h.averagePrice());
                    }
                }
            } catch (Exception e) {
                log.warn(
                        "[CAR-WEEKLY] Error checking {} live holdings: {}",
                        gw.getBrokerName(),
                        e.getMessage());
            }
        }
    }

    // ------------------------------------------------------------------ step 2: fills

    /**
     * Reads the broker status of every trigger we track and books the consequences of a fill.
     *
     * <p>Without this the state machine is frozen: a bought position never becomes a holding (so
     * its capital is never released and it blocks every later buy), a sold position never books
     * realized PnL (so compounding never happens), and a locally stale entry keeps reserving a unit
     * forever.
     */
    private void reconcileFillEvents(GttExecutionGateway gateway) {
        Map<String, CarGttOrder> orders = portfolioState.getGttOrders();
        for (Map.Entry<String, CarGttOrder> entry : new ArrayList<>(orders.entrySet())) {
            String key = entry.getKey();
            CarGttOrder order = entry.getValue();
            if (order == null) {
                orders.remove(key);
                continue;
            }
            if (order.gttId() == null || order.gttId().isBlank()) {
                // Unusable entry (no id) - it only reserves capital and can never be reconciled.
                log.warn("[CAR-WEEKLY] Dropping trigger state with no broker id for {}.", key);
                orders.remove(key);
                continue;
            }

            GttStatus status;
            try {
                status = gateway.getGttStatus(order.gttId());
            } catch (Exception e) {
                log.warn(
                        "[CAR-WEEKLY] Could not read status of trigger {} for {}: {}",
                        order.gttId(),
                        key,
                        e.getMessage());
                continue;
            }
            if (status == null || status == GttStatus.PENDING) {
                // null means "unknown / unreadable" - never guess a terminal state from that.
                continue;
            }

            if (status == GttStatus.TRIGGERED) {
                handleTriggered(key, order, gateway);
            } else if (status.isTerminalNotTriggered()) {
                log.info(
                        "[CAR-WEEKLY] Trigger {} for {} is {} - releasing its capital reservation.",
                        order.gttId(),
                        key,
                        status);
                orders.remove(key);
            }
        }
    }

    private void handleTriggered(String key, CarGttOrder order, GttExecutionGateway gateway) {
        GttFill fill = null;
        try {
            fill = gateway.getTriggerFill(order.gttId());
        } catch (Exception e) {
            log.warn(
                    "[CAR-WEEKLY] Could not read fill for trigger {}: {}",
                    order.gttId(),
                    e.getMessage());
        }

        BigDecimal price = fill != null ? fill.price() : order.limitPrice();
        int quantity = fill != null ? fill.quantity() : order.quantity();
        if (price == null || price.signum() <= 0 || quantity <= 0) {
            // Keep the entry so the fill is retried next run rather than silently dropped.
            log.error(
                    "[CAR-WEEKLY] Trigger {} for {} fired but its fill could not be determined;"
                            + " will retry next run.",
                    order.gttId(),
                    key);
            return;
        }

        if (order.type() == GttOrderType.SELL_TARGET) {
            String symbol =
                    key.endsWith(SELL_KEY_SUFFIX)
                            ? key.substring(0, key.length() - SELL_KEY_SUFFIX.length())
                            : order.symbol();
            BigDecimal pnl = portfolioState.closeHoldingAtTarget(symbol, price);
            log.info(
                    "[CAR-WEEKLY] SELL target for {} filled: {} shares @ ₹{} -> realized PnL ₹{}."
                            + " Capital compounds to ₹{}.",
                    symbol,
                    quantity,
                    price,
                    pnl,
                    portfolioState.getTotalCapital());
            alert(
                    String.format(
                            "[CAR] Target hit for %s: %d sh @ ₹%.2f | PnL ₹%.2f | Capital ₹%.2f",
                            symbol, quantity, price, pnl, portfolioState.getTotalCapital()));
        } else {
            portfolioState.addFill(order.symbol(), quantity, price);
            log.info(
                    "[CAR-WEEKLY] BUY trigger for {} filled: {} shares @ ₹{}. Holding created;"
                            + " sell target will be armed this run.",
                    order.symbol(),
                    quantity,
                    price);
            alert(
                    String.format(
                            "[CAR] Bought %d sh of %s @ ₹%.2f", quantity, order.symbol(), price));
        }

        portfolioState.getGttOrders().remove(key);
    }

    // ------------------------------------------------------------------ step 3: dedup

    /**
     * Reconciles our stored trigger ids against the triggers the broker actually has.
     *
     * <p>Duplicate detection is keyed by <em>symbol + transaction type</em>, never by symbol alone:
     * a held position legitimately carries a BUY accumulation trigger and a SELL target trigger on
     * the same symbol at the same time, and collapsing them into one group would cancel one of the
     * two.
     *
     * <p>Only triggers that are byte-for-byte duplicates of an order we already track are ever
     * cancelled - a manual or third-party trigger at a different price or size is left untouched.
     */
    private void syncLiveTriggers(GttExecutionGateway gateway) {
        List<LiveGttTrigger> active;
        try {
            active = gateway.listActiveGtts();
        } catch (Exception e) {
            log.warn("[CAR-WEEKLY] Could not list live triggers: {}", e.getMessage());
            return;
        }
        if (active == null || active.isEmpty()) {
            return;
        }

        Map<String, CarGttOrder> orders = portfolioState.getGttOrders();

        Map<String, List<LiveGttTrigger>> groups = new LinkedHashMap<>();
        for (LiveGttTrigger t : active) {
            if (t == null || t.symbol() == null || t.symbol().isBlank()) continue;
            String groupKey = t.symbol() + "|" + t.transactionType();
            groups.computeIfAbsent(groupKey, k -> new ArrayList<>()).add(t);
        }

        for (List<LiveGttTrigger> group : groups.values()) {
            reconcileGroup(gateway, group, orders);
        }
    }

    private void reconcileGroup(
            GttExecutionGateway gateway,
            List<LiveGttTrigger> group,
            Map<String, CarGttOrder> orders) {
        LiveGttTrigger sample = group.get(0);
        boolean isSell = isSellType(sample.transactionType());
        String stateKey = isSell ? sample.symbol() + SELL_KEY_SUFFIX : sample.symbol();

        CarGttOrder owned = orders.get(stateKey);
        String ownedId =
                owned != null && owned.gttId() != null && !owned.gttId().isBlank()
                        ? owned.gttId()
                        : null;

        if (ownedId == null) {
            // State entry lost (state file deleted or rewritten) - re-adopt the newest usable
            // trigger so we stop double-arming this symbol on the next placement.
            ownedId = adoptUntrackedTrigger(gateway.getBrokerName(), sample, group, orders);
            owned = orders.get(stateKey);
            if (ownedId == null) {
                return;
            }
        }

        String finalOwnedId = ownedId;
        for (LiveGttTrigger t : group) {
            if (finalOwnedId.equals(t.triggerId()) || !isSameOrder(t, owned)) {
                continue;
            }
            if (gateway.cancelGtt(t.triggerId())) {
                log.warn(
                        "[CAR-WEEKLY] Cancelled duplicate {} trigger {} for {} (kept {}).",
                        t.transactionType(),
                        t.triggerId(),
                        t.symbol(),
                        finalOwnedId);
            }
        }
    }

    /** True when a live trigger describes exactly the order we already track in state. */
    private static boolean isSameOrder(LiveGttTrigger live, CarGttOrder owned) {
        if (live == null || owned == null) {
            return false;
        }
        if (live.quantity() != owned.quantity()) {
            return false;
        }
        return live.triggerValue() != null
                && owned.triggerPrice() != null
                && live.triggerValue().compareTo(owned.triggerPrice()) == 0;
    }

    /**
     * Re-adopts a live trigger whose id is missing from our state (state file lost or rewritten).
     * Adoption requires a real trigger value and quantity so that the entry reserves the correct
     * amount of capital instead of the placeholder entries that used to consume a unit each.
     *
     * @return the adopted trigger id, or {@code null} when nothing usable could be adopted
     */
    private String adoptUntrackedTrigger(
            String brokerName,
            LiveGttTrigger sample,
            List<LiveGttTrigger> group,
            Map<String, CarGttOrder> orders) {
        if (group == null || group.isEmpty()) {
            return null;
        }
        boolean isSell = isSellType(sample.transactionType());
        String stateKey = isSell ? sample.symbol() + SELL_KEY_SUFFIX : sample.symbol();
        CarGttOrder existing = orders.get(stateKey);
        if (existing != null && existing.gttId() != null && !existing.gttId().isBlank()) {
            return existing.gttId();
        }

        LiveGttTrigger pick = sortNewestFirst(group).get(0);
        if (pick.triggerValue() == null
                || pick.triggerValue().signum() <= 0
                || pick.quantity() <= 0) {
            return null;
        }

        BigDecimal limitPrice =
                isSell
                        ? pick.triggerValue()
                        : pick.triggerValue()
                                .add(BigDecimal.valueOf(properties.getTriggerBuffer()));

        log.warn(
                "[CAR-WEEKLY] Adopting pre-existing {} trigger {} for {} (@ ₹{}, qty {}) into"
                        + " portfolio state.",
                pick.transactionType(),
                pick.triggerId(),
                pick.symbol(),
                pick.triggerValue(),
                pick.quantity());
        orders.put(
                stateKey,
                new CarGttOrder(
                        pick.triggerId(),
                        brokerName,
                        pick.symbol(),
                        isSell ? GttOrderType.SELL_TARGET : GttOrderType.BUY,
                        pick.triggerValue(),
                        limitPrice,
                        pick.quantity(),
                        GttStatus.PENDING,
                        CarWeekCalendar.activeWeekStart(),
                        pick.createdAt() != null ? pick.createdAt() : Instant.now()));
        return pick.triggerId();
    }

    private static boolean isSellType(String transactionType) {
        return "SELL".equalsIgnoreCase(transactionType);
    }

    private static List<LiveGttTrigger> sortNewestFirst(List<LiveGttTrigger> triggers) {
        List<LiveGttTrigger> copy = new ArrayList<>(triggers);
        copy.sort(
                (a, b) -> {
                    if (a.createdAt() != null && b.createdAt() != null) {
                        int cmp = b.createdAt().compareTo(a.createdAt());
                        if (cmp != 0) return cmp;
                    } else if (a.createdAt() != null) {
                        return -1;
                    } else if (b.createdAt() != null) {
                        return 1;
                    }
                    // Deterministic tie-break: newest numeric id wins.
                    return Long.compare(parseId(b.triggerId()), parseId(a.triggerId()));
                });
        return copy;
    }

    private static long parseId(String id) {
        try {
            return Long.parseLong(id);
        } catch (Exception e) {
            return 0L;
        }
    }

    // ------------------------------------------------------------------ step 4: scan

    private record ScanResult(
            List<CarAnalysisResult> results,
            Set<String> positiveSymbols,
            Set<String> evaluatedSymbols) {}

    private ScanResult scanUniverse(Set<String> allHeldSymbols) {
        Set<String> universe = Nifty100Registry.getUniverseWithHoldings(allHeldSymbols);
        List<CarAnalysisResult> positives = new ArrayList<>();
        Set<String> positiveSymbols = new HashSet<>();
        Set<String> evaluated = new HashSet<>();

        if (ohlcService != null) {
            for (String symbol : universe) {
                try {
                    List<Candle> dailyCandles = ohlcService.getDailyCandles(symbol);
                    if (dailyCandles == null || dailyCandles.isEmpty()) {
                        continue;
                    }
                    CarAnalysisResult res = calculator.analyze(symbol, dailyCandles);
                    if (res == null) continue;
                    evaluated.add(symbol);
                    if (res.isCarPositive()) {
                        positives.add(res);
                        positiveSymbols.add(symbol);
                    }
                } catch (Exception e) {
                    // A single bad symbol must not abort the run before state is saved.
                    log.warn("[CAR-WEEKLY] Analysis failed for {}: {}", symbol, e.getMessage());
                }
            }
        }

        log.info(
                "[CAR-WEEKLY] Evaluated {} universe stocks. Found {} CAR-Positive candidates.",
                evaluated.size(),
                positives.size());
        return new ScanResult(positives, positiveSymbols, evaluated);
    }

    // ------------------------------------------------------------------ step 5: CAR-negative

    /**
     * Spec 3.1, row 3: a pending buy GTT whose symbol is no longer CAR-positive is deleted so stale
     * triggers are never left hanging in the market. Only symbols we actually evaluated are
     * cancelled - a data outage must not wipe live orders.
     */
    private void cancelBuyGttsForNonPositive(
            GttExecutionGateway gateway, Set<String> evaluated, Set<String> positive) {
        Map<String, CarGttOrder> orders = portfolioState.getGttOrders();
        for (Map.Entry<String, CarGttOrder> entry : new ArrayList<>(orders.entrySet())) {
            String symbol = entry.getKey();
            CarGttOrder order = entry.getValue();
            if (order == null
                    || order.type() != GttOrderType.BUY
                    || order.status() != GttStatus.PENDING
                    || !evaluated.contains(symbol)
                    || positive.contains(symbol)) {
                continue;
            }
            log.info(
                    "[CAR-WEEKLY] {} is no longer CAR-positive. Cancelling pending buy GTT {}.",
                    symbol,
                    order.gttId());
            if (order.gttId() != null && !order.gttId().isBlank()) {
                gateway.cancelGtt(order.gttId());
            }
            orders.remove(symbol);
        }
    }

    // ------------------------------------------------------------------ step 6: sell targets

    private void manageSellTargetGtts(GttExecutionGateway gateway, LocalDate weekStart) {
        for (CarHolding holding : portfolioState.getHoldings().values()) {
            String sym = holding.symbol();
            if (properties.isAccumulationOnly(sym)) {
                log.info(
                        "[CAR-WEEKLY] Symbol {} is configured as Accumulation-Only / SGB. Skipping"
                                + " sell target GTT placement.",
                        sym);
                continue;
            }
            if (holding.totalQuantity() <= 0
                    || holding.targetPrice() == null
                    || holding.targetPrice().signum() <= 0) {
                continue;
            }
            try {
                placeOrRefreshSellTarget(gateway, sym, holding, weekStart);
            } catch (Exception e) {
                log.error(
                        "[CAR-WEEKLY] Could not manage sell target for {}: {}",
                        sym,
                        e.getMessage(),
                        e);
            }
        }
    }

    private void placeOrRefreshSellTarget(
            GttExecutionGateway gateway, String sym, CarHolding holding, LocalDate weekStart) {
        String sellKey = sym + SELL_KEY_SUFFIX;
        BigDecimal target = holding.targetPrice();
        int qty = (int) holding.totalQuantity();

        CarGttOrder existing = portfolioState.getGttOrders().get(sellKey);
        boolean upToDate =
                existing != null
                        && existing.status() == GttStatus.PENDING
                        && existing.gttId() != null
                        && !existing.gttId().isBlank()
                        && existing.triggerPrice() != null
                        && existing.triggerPrice().compareTo(target) == 0
                        && existing.quantity() == qty;
        if (upToDate) {
            log.info(
                    "[CAR-WEEKLY] Active Sell Target GTT already exists for {} (ID: {}, Target:"
                            + " ₹{}, Qty: {}). Skipping duplicate.",
                    sym,
                    existing.gttId(),
                    target,
                    qty);
            return;
        }

        CarGttOrder desired =
                new CarGttOrder(
                        null,
                        gateway.getBrokerName(),
                        sym,
                        GttOrderType.SELL_TARGET,
                        target,
                        target,
                        qty,
                        GttStatus.PENDING,
                        weekStart,
                        Instant.now());

        String effectiveId;
        if (existing != null && existing.gttId() != null && !existing.gttId().isBlank()) {
            log.info(
                    "[CAR-WEEKLY] Modifying Sell Target GTT for {}: old target ₹{} -> ₹{}, old qty"
                            + " {} -> {}",
                    sym,
                    existing.triggerPrice(),
                    target,
                    existing.quantity(),
                    qty);
            effectiveId = gateway.modifyGtt(existing.gttId(), desired);
        } else {
            effectiveId = gateway.placeGtt(desired);
        }

        if (effectiveId == null || effectiveId.isBlank()) {
            log.error(
                    "[CAR-WEEKLY] Could not refresh sell target for {} - keeping previous state so"
                            + " the next run retries.",
                    sym);
            alert("[CAR] Failed to refresh sell target for " + sym + " - will retry next run.");
            return;
        }

        portfolioState.getGttOrders().put(sellKey, desired.withId(effectiveId));
        log.info(
                "[CAR-WEEKLY] Sell Target GTT for {} on {}: {} shares @ ₹{} (ID: {})",
                sym,
                gateway.getBrokerName(),
                qty,
                target,
                effectiveId);
    }

    // ------------------------------------------------------------------ step 7: buy gtts

    private void reconcileBuyGtts(
            List<CarAnalysisResult> carPositiveStocks,
            GttExecutionGateway gateway,
            LocalDate weekStart) {
        if (carPositiveStocks == null || carPositiveStocks.isEmpty()) {
            return;
        }

        for (CarAnalysisResult candidate : carPositiveStocks) {
            if (portfolioState.getAvailableUnits() <= 0) {
                log.info(
                        "[CAR-WEEKLY] All {} capital units allocated. Standing by.",
                        properties.getNumParts());
                break;
            }
            try {
                reconcileBuyGttFor(candidate, gateway, weekStart);
            } catch (Exception e) {
                log.error(
                        "[CAR-WEEKLY] Could not reconcile buy GTT for {}: {}",
                        candidate.symbol(),
                        e.getMessage(),
                        e);
            }
        }
    }

    private void reconcileBuyGttFor(
            CarAnalysisResult candidate, GttExecutionGateway gateway, LocalDate weekStart) {
        if (ohlcService == null) {
            return;
        }
        List<Candle> allCandles = ohlcService.getDailyCandles(candidate.symbol());
        if (allCandles == null || allCandles.isEmpty()) {
            return;
        }

        List<Candle> lastWeekCandles = CarWeekCalendar.previousCompletedWeek(allCandles, weekStart);
        if (lastWeekCandles.isEmpty()) {
            log.warn(
                    "[CAR-WEEKLY] No completed-week candles for {} before {}. Nothing to arm.",
                    candidate.symbol(),
                    weekStart);
            return;
        }

        CarWeeklyTriggerGenerator.TriggerCalculation trig =
                triggerGenerator.calculateTrigger(
                        candidate.symbol(), lastWeekCandles, portfolioState.getUnitSize());
        if (trig.quantity() <= 0 || trig.triggerPrice().signum() <= 0) {
            return;
        }

        CarGttOrder desired =
                new CarGttOrder(
                        null,
                        gateway.getBrokerName(),
                        candidate.symbol(),
                        GttOrderType.BUY,
                        trig.triggerPrice(),
                        trig.limitPrice(),
                        trig.quantity(),
                        GttStatus.PENDING,
                        weekStart,
                        Instant.now());

        CarGttOrder existing = portfolioState.getGttOrders().get(candidate.symbol());
        String effectiveId = null;

        if (existing != null
                && existing.status() == GttStatus.PENDING
                && existing.gttId() != null
                && !existing.gttId().isBlank()
                && existing.triggerPrice() != null
                && existing.triggerPrice().signum() > 0) {
            if (existing.triggerPrice().compareTo(trig.triggerPrice()) == 0
                    && existing.quantity() == trig.quantity()) {
                log.info(
                        "[CAR-WEEKLY] Active GTT trigger already exists for {} on {} (ID: {},"
                                + " Trigger: ₹{}, Qty: {}). Skipping duplicate placement.",
                        candidate.symbol(),
                        existing.broker(),
                        existing.gttId(),
                        existing.triggerPrice(),
                        existing.quantity());
                return;
            }
            log.info(
                    "[CAR-WEEKLY] Modifying GTT for {}: old trigger ₹{} -> ₹{}, old qty {} -> {}",
                    candidate.symbol(),
                    existing.triggerPrice(),
                    trig.triggerPrice(),
                    existing.quantity(),
                    trig.quantity());
            effectiveId = gateway.modifyGtt(existing.gttId(), desired);
            if (effectiveId == null || effectiveId.isBlank()) {
                // Keep the previous entry: the old trigger may still be live, and dropping it
                // would lose the id we need to cancel or modify it next run.
                log.error(
                        "[CAR-WEEKLY] Failed to refresh buy GTT for {} - keeping previous state.",
                        candidate.symbol());
                alert(
                        "[CAR] Failed to refresh buy GTT for "
                                + candidate.symbol()
                                + " - will retry.");
                return;
            }
        } else {
            effectiveId = gateway.placeGtt(desired);
            if (effectiveId == null || effectiveId.isBlank()) {
                log.error("[CAR-WEEKLY] Could not place buy GTT for {}.", candidate.symbol());
                alert("[CAR] Failed to place buy GTT for " + candidate.symbol() + " - will retry.");
                return;
            }
        }

        portfolioState.getGttOrders().put(candidate.symbol(), desired.withId(effectiveId));
        log.info(
                "[CAR-WEEKLY] Buy GTT for {} on {}: {} shares @ trigger ₹{} limit ₹{} (ID: {})",
                candidate.symbol(),
                gateway.getBrokerName(),
                trig.quantity(),
                trig.triggerPrice(),
                trig.limitPrice(),
                effectiveId);
    }

    // ------------------------------------------------------------------ reporting

    private void sendSundayTelegramReport(List<CarAnalysisResult> carPositives) {
        if (!properties.isTelegramAlerts() || telegramService == null) return;
        StringBuilder sb = new StringBuilder();
        sb.append("📈 *CAR Weekly GTT Sunday Report*\n\n");
        sb.append(
                String.format(
                        "• *Total Capital:* `₹%.2f`\n",
                        portfolioState.getTotalCapital().doubleValue()));
        sb.append(
                String.format(
                        "• *UNIT Spend (1/40th):* `₹%.2f` (Available Units: %d/%d)\n",
                        portfolioState.getUnitSize().doubleValue(),
                        portfolioState.getAvailableUnits(),
                        properties.getNumParts()));
        sb.append(
                String.format(
                        "• *Active Holdings:* `%d` | *CAR-Positive Stocks:* `%d`\n\n",
                        portfolioState.getHoldings().size(), carPositives.size()));

        if (!carPositives.isEmpty()) {
            sb.append("🎯 *Top CAR-Positive Setups (Buy Above Last Week High):*\n");
            for (int i = 0; i < Math.min(10, carPositives.size()); i++) {
                CarAnalysisResult c = carPositives.get(i);
                boolean isExempt = properties.isAccumulationOnly(c.symbol());
                BigDecimal lastWkHigh =
                        c.lastWeekHigh() != null && c.lastWeekHigh().compareTo(BigDecimal.ZERO) > 0
                                ? c.lastWeekHigh()
                                : c.latestClose();
                int trigQty = 1;
                if (portfolioState.getUnitSize().compareTo(BigDecimal.ZERO) > 0
                        && lastWkHigh.compareTo(BigDecimal.ZERO) > 0) {
                    trigQty =
                            (int)
                                    Math.ceil(
                                            portfolioState
                                                    .getUnitSize()
                                                    .divide(lastWkHigh, 4, RoundingMode.HALF_UP)
                                                    .doubleValue());
                    if (trigQty <= 0) trigQty = 1;
                }

                sb.append(
                        String.format(
                                " • *%s*%s (Streak: %d days | Last Week High: `₹%.2f` | Qty: `%d`)\n",
                                c.symbol(),
                                isExempt ? " 🛡️ _(Accumulate Only)_" : "",
                                c.consecutivePositiveDays(),
                                lastWkHigh.doubleValue(),
                                trigQty));
            }
        }

        telegramService.sendTextMessage(sb.toString());
    }

    private void alert(String message) {
        if (telegramService == null || !properties.isTelegramAlerts()) return;
        try {
            telegramService.sendTextMessage(message);
        } catch (Exception e) {
            log.warn("[CAR-WEEKLY] Could not send alert: {}", e.getMessage());
        }
    }

    // ------------------------------------------------------------------ persistence

    private void saveState() {
        try {
            File target = new File(properties.getStateFilePath());
            if (target.getParentFile() != null) target.getParentFile().mkdirs();

            File temp = new File(target.getAbsolutePath() + ".tmp");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(temp, portfolioState);
            try {
                Files.move(
                        temp.toPath(),
                        target.toPath(),
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception atomicUnavailable) {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            log.error("[CAR-WEEKLY] Failed saving state: {}", e.getMessage(), e);
        }
    }

    private void loadState() {
        try {
            File f = new File(properties.getStateFilePath());
            if (f.exists() && f.length() > 0) {
                this.portfolioState = objectMapper.readValue(f, CarPortfolioState.class);
                log.info(
                        "[CAR-WEEKLY] Loaded portfolio state: {} holdings, {} tracked triggers,"
                                + " last run week {}",
                        portfolioState.getHoldings().size(),
                        portfolioState.getGttOrders().size(),
                        portfolioState.getLastRunWeek());
            }
        } catch (Exception e) {
            log.warn(
                    "[CAR-WEEKLY] Could not load state from {} (will start with fresh state): {}",
                    properties.getStateFilePath(),
                    e.getMessage());
        }
    }

    public CarPortfolioState getPortfolioState() {
        return portfolioState;
    }

    /** Exposed for tests and diagnostics: the gateway the current mode would use. */
    public String resolveGatewayBrokerName() {
        GttExecutionGateway gw = resolveGateway();
        return gw != null ? gw.getBrokerName() : null;
    }
}
