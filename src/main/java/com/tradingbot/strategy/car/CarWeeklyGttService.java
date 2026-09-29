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
    private final ObjectMapper objectMapper = new ObjectMapper();

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

        Set<String> universe =
                Nifty100Registry.getUniverseWithHoldings(portfolioState.getHoldings().keySet());
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

        saveState();
        sendSundayTelegramReport(carPositiveStocks);
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
                        "• *UNIT Spend:* `₹%.2f` (Available Units: %d/%d)\n",
                        portfolioState.getUnitSize().doubleValue(),
                        portfolioState.getAvailableUnits(),
                        properties.getNumParts()));
        sb.append(
                String.format(
                        "• *Active Holdings:* `%d` | *CAR-Positive Stocks:* `%d`\n\n",
                        portfolioState.getHoldings().size(),
                        carPositives.size()));

        if (!carPositives.isEmpty()) {
            sb.append("🎯 *Top CAR-Positive Setups:*\n");
            for (int i = 0; i < Math.min(5, carPositives.size()); i++) {
                CarAnalysisResult c = carPositives.get(i);
                sb.append(
                        String.format(
                                " • *%s* (Streak: %d days | 52W High: ₹%.2f)\n",
                                c.symbol(),
                                c.consecutivePositiveDays(),
                                c.fiftyTwoWeekHighClose().doubleValue()));
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
            if (f.exists()) {
                this.portfolioState = objectMapper.readValue(f, CarPortfolioState.class);
                log.info(
                        "[CAR-WEEKLY] Loaded portfolio state: {} holdings",
                        portfolioState.getHoldings().size());
            }
        } catch (Exception e) {
            log.warn("[CAR-WEEKLY] Could not load state: {}", e.getMessage());
        }
    }

    public CarPortfolioState getPortfolioState() {
        return portfolioState;
    }
}
