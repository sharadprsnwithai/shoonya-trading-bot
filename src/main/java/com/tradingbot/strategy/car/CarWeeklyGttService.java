package com.tradingbot.strategy.car;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingbot.marketdata.HistoricalOhlcCacheService;
import com.tradingbot.model.Candle;
import com.tradingbot.strategy.car.config.CarWeeklyProperties;
import com.tradingbot.strategy.car.gtt.GttExecutionGateway;
import com.tradingbot.strategy.car.model.*;
import com.tradingbot.strategy.car.universe.Nifty100Registry;
import com.tradingbot.telegram.TelegramService;
import jakarta.annotation.PostConstruct;
import java.io.File;
import java.math.BigDecimal;
import java.math.RoundingMode;
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

    private final CarWeeklyProperties properties;
    private final CarCalculator calculator;
    private final CarWeeklyTriggerGenerator triggerGenerator;
    private final HistoricalOhlcCacheService ohlcService;
    private final List<GttExecutionGateway> gttGateways;
    private final TelegramService telegramService;
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
            @Autowired(required = false) TelegramService telegramService) {
        this.properties = properties;
        this.calculator = calculator;
        this.triggerGenerator = triggerGenerator;
        this.ohlcService = ohlcService;
        this.gttGateways = gttGateways != null ? gttGateways : List.of();
        this.telegramService = telegramService;
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

    public synchronized void runSundayWeeklyRoutine() {
        if (!properties.isEnabled()) {
            log.info("[CAR-WEEKLY] Strategy is disabled in configuration.");
            return;
        }

        log.info("==================================================================");
        log.info("       CAR WEEKLY GTT STRATEGY - SUNDAY RECONCILIATION            ");
        log.info("==================================================================");

        BigDecimal unitSize = portfolioState.getUnitSize();
        int availableUnits = portfolioState.getAvailableUnits();
        log.info(
                "[CAR-WEEKLY] Total Capital: ₹{} | UNIT Size: ₹{} | Available Units: {}/{}",
                portfolioState.getTotalCapital(),
                unitSize,
                availableUnits,
                properties.getNumParts());

        // 1. Sync live broker Demat holdings (Zerodha + Shoonya) into portfolio state if enabled
        Set<String> allHeldSymbols = new HashSet<>(portfolioState.getHoldings().keySet());
        if (properties.isSyncDematHoldings()) {
            for (GttExecutionGateway gw : gttGateways) {
                try {
                    List<com.tradingbot.model.execution.BrokerPosition> brokerHoldings =
                            gw.getHoldings();
                    if (brokerHoldings != null) {
                        for (com.tradingbot.model.execution.BrokerPosition h : brokerHoldings) {
                            String sym = h.symbol();
                            allHeldSymbols.add(sym);
                            if (!properties.isAccumulationOnly(sym)
                                    && !portfolioState.getHoldings().containsKey(sym)) {
                                log.info(
                                        "[CAR-WEEKLY] Discovered live {} Demat holding for {}: {}"
                                                + " shares @ avg ₹{}",
                                        gw.getBrokerName(),
                                        sym,
                                        h.quantity(),
                                        h.averagePrice());
                                portfolioState.addFill(sym, (int) h.quantity(), h.averagePrice());
                            }
                        }
                    }
                } catch (Exception e) {
                    log.warn(
                            "[CAR-WEEKLY] Notice checking {} live holdings: {}",
                            gw.getBrokerName(),
                            e.getMessage());
                }
            }
        }

        // 2. Build full universe: Nifty 100 + CAR local holdings + live Broker Demat holdings!
        Set<String> universe = Nifty100Registry.getUniverseWithHoldings(allHeldSymbols);
        List<CarAnalysisResult> carPositiveStocks = new ArrayList<>();

        for (String symbol : universe) {
            if (ohlcService != null) {
                List<Candle> dailyCandles = ohlcService.getDailyCandles(symbol);
                if (dailyCandles != null && !dailyCandles.isEmpty()) {
                    CarAnalysisResult res = calculator.analyze(symbol, dailyCandles);
                    if (res.isCarPositive()) {
                        carPositiveStocks.add(res);
                    }
                }
            }
        }

        log.info(
                "[CAR-WEEKLY] Evaluated {} universe stocks. Found {} CAR-Positive candidates.",
                universe.size(),
                carPositiveStocks.size());

        // 3. Manage Sell Targets for Holdings (Skipping Accumulation-Only / SGB symbols)
        manageSellTargetGtts();

        // 4. Place / Modify Buy GTTs for CAR-Positive stocks up to available capital units
        reconcileBuyGtts(carPositiveStocks);

        saveState();
        sendSundayTelegramReport(carPositiveStocks);
    }

    private void manageSellTargetGtts() {
        for (CarHolding holding : portfolioState.getHoldings().values()) {
            String sym = holding.symbol();
            if (properties.isAccumulationOnly(sym)) {
                log.info(
                        "[CAR-WEEKLY] Symbol {} is configured as Accumulation-Only / SGB."
                                + " Skipping +6.28% sell target GTT placement.",
                        sym);
                continue;
            }

            // For standard stocks, ensure target GTT is active at holding.targetPrice()
            log.info(
                    "[CAR-WEEKLY] Tracking +6.28% profit target for {}: {} shares @ Target ₹{}",
                    sym, holding.totalQuantity(), holding.targetPrice());
        }
    }

    private void reconcileBuyGtts(List<CarAnalysisResult> carPositiveStocks) {
        if (carPositiveStocks == null || carPositiveStocks.isEmpty()) return;

        LocalDate monday = LocalDate.now(IST);
        while (monday.getDayOfWeek().getValue() != 1) {
            monday = monday.minusDays(1);
        }

        for (CarAnalysisResult candidate : carPositiveStocks) {
            if (portfolioState.getAvailableUnits() <= 0) {
                log.info("[CAR-WEEKLY] All 40 capital units allocated. Standing by.");
                break;
            }

            String sym = candidate.symbol();
            if (ohlcService != null) {
                List<Candle> allCandles = ohlcService.getDailyCandles(sym);
                if (allCandles != null && !allCandles.isEmpty()) {
                    int start5 = Math.max(0, allCandles.size() - 5);
                    List<Candle> lastWeekCandles = allCandles.subList(start5, allCandles.size());
                    CarWeeklyTriggerGenerator.TriggerCalculation trig =
                            triggerGenerator.calculateTrigger(
                                    sym, lastWeekCandles, portfolioState.getUnitSize());

                    if (trig.quantity() > 0 && trig.triggerPrice().compareTo(BigDecimal.ZERO) > 0) {
                        CarGttOrder gtt =
                                new CarGttOrder(
                                        null,
                                        "PRIMARY",
                                        sym,
                                        GttOrderType.BUY,
                                        trig.triggerPrice(),
                                        trig.limitPrice(),
                                        trig.quantity(),
                                        GttStatus.PENDING,
                                        monday,
                                        Instant.now());

                        for (GttExecutionGateway gw : gttGateways) {
                            try {
                                String gttId = gw.placeGtt(gtt);
                                if (gttId != null) {
                                    portfolioState
                                            .getGttOrders()
                                            .put(
                                                    sym,
                                                    new CarGttOrder(
                                                            gttId,
                                                            gw.getBrokerName(),
                                                            sym,
                                                            gtt.type(),
                                                            gtt.triggerPrice(),
                                                            gtt.limitPrice(),
                                                            gtt.quantity(),
                                                            GttStatus.PENDING,
                                                            monday,
                                                            Instant.now()));
                                }
                            } catch (Exception e) {
                                log.warn(
                                        "[CAR-WEEKLY] Could not place GTT for {} on {}: {}",
                                        sym,
                                        gw.getBrokerName(),
                                        e.getMessage());
                            }
                        }
                    }
                }
            }
        }
    }

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
                        c.lastWeekHigh() != null
                                        && c.lastWeekHigh().compareTo(BigDecimal.ZERO) > 0
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

    private void saveState() {
        try {
            File f = new File(properties.getStateFilePath());
            if (f.getParentFile() != null) f.getParentFile().mkdirs();
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(f, portfolioState);
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
                        "[CAR-WEEKLY] Loaded portfolio state: {} holdings",
                        portfolioState.getHoldings().size());
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
}
