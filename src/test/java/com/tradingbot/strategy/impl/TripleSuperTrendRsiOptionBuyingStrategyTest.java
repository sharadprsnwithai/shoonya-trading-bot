package com.tradingbot.strategy.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.tradingbot.indicator.TechnicalAnalysisService;
import com.tradingbot.marketdata.ShoonyaOptionChainService;
import com.tradingbot.model.Candle;
import com.tradingbot.model.execution.TripleSuperTrendPosition;
import com.tradingbot.strategy.SignalAction;
import com.tradingbot.strategy.TradeSignal;
import com.tradingbot.telegram.TelegramService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TripleSuperTrendRsiOptionBuyingStrategyTest {

    private TechnicalAnalysisService taService;
    private ShoonyaOptionChainService optionChainService;
    private TelegramService telegramService;
    private TripleSuperTrendRsiOptionBuyingStrategy strategy;

    @BeforeEach
    void setUp() {
        taService = new TechnicalAnalysisService();
        optionChainService = mock(ShoonyaOptionChainService.class);
        telegramService = mock(TelegramService.class);
        strategy =
                new TripleSuperTrendRsiOptionBuyingStrategy(
                        taService, optionChainService, telegramService);
        strategy.setEnabled(true);
        strategy.setCreditSpreadEnabled(false);
        strategy.setAdxFilterEnabled(false);
        strategy.setRsiBullishMax(100.0);
        strategy.setRsiBearishMin(0.0);
    }

    private Candle createCandle(
            String symbol, String timeStr, double open, double high, double low, double close) {
        return new Candle(
                symbol,
                "60",
                Instant.parse(timeStr),
                BigDecimal.valueOf(open),
                BigDecimal.valueOf(high),
                BigDecimal.valueOf(low),
                BigDecimal.valueOf(close),
                50000);
    }

    private List<Candle> generateUptrendCandles(
            String symbol, int count, double startPrice, double step) {
        List<Candle> candles = new ArrayList<>();
        Instant start = Instant.parse("2026-09-01T04:00:00Z");
        double p = startPrice;
        for (int i = 0; i < count; i++) {
            Instant t = start.plusSeconds(i * 3600);
            candles.add(createCandle(symbol, t.toString(), p, p + step + 0.5, p - 0.2, p + step));
            p += step;
        }
        return candles;
    }

    private List<Candle> generateDowntrendCandles(
            String symbol, int count, double startPrice, double step) {
        List<Candle> candles = new ArrayList<>();
        Instant start = Instant.parse("2026-09-01T04:00:00Z");
        double p = startPrice;
        for (int i = 0; i < count; i++) {
            Instant t = start.plusSeconds(i * 3600);
            candles.add(createCandle(symbol, t.toString(), p, p + 0.2, p - step - 0.5, p - step));
            p -= step;
        }
        return candles;
    }

    @Test
    void testBullishConfluenceGeneratesShortAtmPutSignalInOptionSellingMode() {
        // Default mode is OPTION_SELLING: Bullish confluence -> Short ATM PE
        List<Candle> history = generateUptrendCandles("NIFTY50", 25, 24000, 20.0);
        Candle latest = history.remove(history.size() - 1);

        TradeSignal signal = strategy.onCandle(latest, history);

        assertThat(signal).isNotNull();
        assertThat(signal.action()).isEqualTo(SignalAction.SELL);
        assertThat(signal.reason()).contains("Bullish Confluence (Short ATM PE)");
        assertThat(signal.metadata()).containsEntry("optionType", "PE");
        assertThat(signal.metadata()).containsEntry("mode", "OPTION_SELLING");

        TripleSuperTrendPosition pos = strategy.getActivePosition("NIFTY50");
        assertThat(pos).isNotNull();
        assertThat(pos.optionType()).isEqualTo("PE");
        assertThat(pos.strike()).isEqualByComparingTo(BigDecimal.valueOf(24500));
    }

    @Test
    void testBearishConfluenceGeneratesShortAtmCallSignalInOptionSellingMode() {
        // Default mode is OPTION_SELLING: Bearish confluence -> Short ATM CE
        List<Candle> history = generateDowntrendCandles("ABB", 25, 8000, 30.0);
        Candle latest = history.remove(history.size() - 1);

        TradeSignal signal = strategy.onCandle(latest, history);

        assertThat(signal).isNotNull();
        assertThat(signal.action()).isEqualTo(SignalAction.SELL);
        assertThat(signal.reason()).contains("Bearish Confluence (Short ATM CE)");
        assertThat(signal.metadata()).containsEntry("optionType", "CE");
        assertThat(signal.metadata()).containsEntry("mode", "OPTION_SELLING");

        TripleSuperTrendPosition pos = strategy.getActivePosition("ABB");
        assertThat(pos).isNotNull();
        assertThat(pos.optionType()).isEqualTo("CE");
    }

    @Test
    void testFastSuperTrendFlipExitsShortPutPosition() {
        // 1. Enter Short Put on Uptrend
        List<Candle> history = generateUptrendCandles("TATASTEEL", 25, 140, 1.0);
        Candle entryCandle = history.remove(history.size() - 1);
        strategy.onCandle(entryCandle, history);
        assertThat(strategy.getActivePosition("TATASTEEL")).isNotNull();
        assertThat(strategy.getActivePosition("TATASTEEL").optionType()).isEqualTo("PE");

        // 2. Generate a sharp drop below Fast SuperTrend -> should exit short PE
        history.add(entryCandle);
        Instant nextTime = entryCandle.timestamp().plusSeconds(3600);
        Candle sharpDrop = createCandle("TATASTEEL", nextTime.toString(), 165, 165, 120, 125);

        TradeSignal exitSignal = strategy.onCandle(sharpDrop, history);

        assertThat(exitSignal.action()).isEqualTo(SignalAction.EXIT_SHORT);
        assertThat(exitSignal.isActionable()).isTrue();
        assertThat(strategy.getActivePosition("TATASTEEL")).isNull();
    }

    @Test
    void testOptionBuyingModeGeneratesBuySignals() {
        // Switch to OPTION_BUYING mode
        strategy.setMode("OPTION_BUYING");

        // Bullish -> Buy ATM CE
        List<Candle> bullHist = generateUptrendCandles("NIFTY50", 25, 24000, 20.0);
        Candle bullLast = bullHist.remove(bullHist.size() - 1);
        TradeSignal bullSignal = strategy.onCandle(bullLast, bullHist);

        assertThat(bullSignal.action()).isEqualTo(SignalAction.BUY);
        assertThat(bullSignal.reason()).contains("Buy ATM CE");
        assertThat(bullSignal.metadata()).containsEntry("optionType", "CE");

        // Bearish -> Buy ATM PE
        List<Candle> bearHist = generateDowntrendCandles("ABB", 25, 8000, 30.0);
        Candle bearLast = bearHist.remove(bearHist.size() - 1);
        TradeSignal bearSignal = strategy.onCandle(bearLast, bearHist);

        assertThat(bearSignal.action()).isEqualTo(SignalAction.BUY);
        assertThat(bearSignal.reason()).contains("Buy ATM PE");
        assertThat(bearSignal.metadata()).containsEntry("optionType", "PE");
    }

    @Test
    void testMultipleSymbolsTrackedIndependently() {
        // Trigger Nifty entry (Short PE in selling mode)
        List<Candle> niftyHist = generateUptrendCandles("NIFTY50", 25, 24000, 20.0);
        Candle niftyLast = niftyHist.remove(niftyHist.size() - 1);
        strategy.onCandle(niftyLast, niftyHist);

        // Trigger ABB entry (Short CE in selling mode)
        List<Candle> abbHist = generateDowntrendCandles("ABB", 25, 8000, 30.0);
        Candle abbLast = abbHist.remove(abbHist.size() - 1);
        strategy.onCandle(abbLast, abbHist);

        assertThat(strategy.getActivePositions()).hasSize(2);
        assertThat(strategy.getActivePosition("NIFTY50").optionType()).isEqualTo("PE");
        assertThat(strategy.getActivePosition("ABB").optionType()).isEqualTo("CE");

        // Square off ABB only
        strategy.squareOffPosition("ABB", null, "Manual Exit");
        assertThat(strategy.getActivePositions()).hasSize(1);
        assertThat(strategy.getActivePosition("NIFTY50")).isNotNull();
        assertThat(strategy.getActivePosition("ABB")).isNull();
    }

    @Test
    void testCreditSpreadOptionSellingCreatesHedgedPositionWithMaxRiskCapped() {
        strategy.setCreditSpreadEnabled(true);
        strategy.setHedgeStrikeOffset(2);

        List<Candle> history = generateUptrendCandles("NIFTY50", 25, 24000, 20.0);
        Candle latest = history.remove(history.size() - 1);

        TradeSignal signal = strategy.onCandle(latest, history);

        assertThat(signal.action()).isEqualTo(SignalAction.SELL);
        assertThat(signal.reason()).contains("Bull Put Credit Spread");

        TripleSuperTrendPosition pos = strategy.getActivePosition("NIFTY50");
        assertThat(pos).isNotNull();
        assertThat(pos.isCreditSpread()).isTrue();
        assertThat(pos.hedgeStrike()).isEqualByComparingTo(BigDecimal.valueOf(24400));
        assertThat(pos.netCredit()).isGreaterThan(BigDecimal.ZERO);
        assertThat(pos.maxRisk()).isGreaterThan(BigDecimal.ZERO);

        // Test max risk capping on catastrophic exit
        BigDecimal catastrophicExitPremium = BigDecimal.valueOf(1000.0);
        TripleSuperTrendPosition closed = pos.close(catastrophicExitPremium, "Test", true);
        // Closed PnL should not be worse than -maxRisk * quantity
        BigDecimal worstPossiblePnl =
                pos.maxRisk().negate().multiply(BigDecimal.valueOf(pos.quantity()));
        assertThat(closed.realizedPnl()).isEqualByComparingTo(worstPossiblePnl);
    }

    @Test
    void testAdxFilterBlocksEntryWhenBelowThreshold() {
        strategy.setAdxFilterEnabled(true);
        strategy.setAdxThreshold(25.0);

        // 25 candles is insufficient for TA-Lib ADX(14) calculation -> latestAdx is NaN
        List<Candle> history = generateUptrendCandles("NIFTY50", 25, 24000, 20.0);
        Candle latest = history.remove(history.size() - 1);

        TradeSignal signal = strategy.onCandle(latest, history);

        assertThat(signal.isActionable()).isFalse();
        assertThat(signal.reason()).contains("filtered");
        assertThat(strategy.getActivePosition("NIFTY50")).isNull();
    }

    @Test
    void testRsiExhaustionCapBlocksOverboughtPutSelling() {
        // With 25 straight upward bars, RSI reaches ~100
        strategy.setRsiBullishMax(68.0);

        List<Candle> history = generateUptrendCandles("NIFTY50", 25, 24000, 20.0);
        Candle latest = history.remove(history.size() - 1);

        TradeSignal signal = strategy.onCandle(latest, history);

        assertThat(signal.isActionable()).isFalse();
        assertThat(strategy.getActivePosition("NIFTY50")).isNull();
    }

    @Test
    void testPostLossCooldownBlocksImmediateReentry() {
        strategy.setCooldownBars(3);

        List<Candle> history = generateUptrendCandles("NIFTY50", 25, 24000, 20.0);
        Candle latest = history.remove(history.size() - 1);
        strategy.onCandle(latest, history);

        // Simulate a loss exit to trigger cooldown
        strategy.squareOffPosition("NIFTY50", BigDecimal.valueOf(1000.0), "Stop Loss Hit");
        assertThat(strategy.getActivePosition("NIFTY50")).isNull();

        // Next candle should be blocked by cooldown
        Candle nextCandle =
                createCandle(
                        "NIFTY50",
                        latest.timestamp().plusSeconds(3600).toString(),
                        24600,
                        24620,
                        24590,
                        24610);
        TradeSignal cooldownSignal = strategy.onCandle(nextCandle, history);

        assertThat(cooldownSignal.isActionable()).isFalse();
        assertThat(cooldownSignal.reason()).contains("cooldown active");
    }

    @Test
    void testCuratedSymbolBasketSelection() {
        strategy.setSymbolBasket("CURATED");
        List<String> curated = strategy.getSubscribedSymbols();
        assertThat(curated).hasSize(10);
        assertThat(curated).contains("VEDL", "GLENMARK", "BSE");

        strategy.setSymbolBasket("ALL");
        List<String> allSymbols = strategy.getSubscribedSymbols();
        assertThat(allSymbols).hasSizeGreaterThanOrEqualTo(30);
    }

    @Test
    void testRejectionWickFilterBlocksUpperShadowTrap() {
        strategy.setRejectionWickFilterEnabled(true);
        strategy.setRejectionWickThreshold(0.60);

        List<Candle> history = generateUptrendCandles("NIFTY50", 25, 24000, 20.0);
        // Replace latest candle with a massive upper shadow rejection bar (close near low)
        Instant t = Instant.parse("2026-09-02T10:00:00Z");
        Candle trapCandle =
                createCandle(
                        "NIFTY50",
                        t.toString(),
                        24490,
                        24560,
                        24480,
                        24485); // range=80, close-low=5 (6.25%)
        history.remove(history.size() - 1);

        TradeSignal signal = strategy.onCandle(trapCandle, history);

        assertThat(signal.isActionable()).isFalse();
        assertThat(strategy.getActivePosition("NIFTY50")).isNull();
    }

    @Test
    void testAtrFilterBlocksDojiCandle() {
        strategy.setAtrFilterEnabled(true);
        strategy.setMinAtrRatio(0.60);
        strategy.setMaxAtrRatio(2.50);

        List<Candle> history = generateUptrendCandles("NIFTY50", 25, 24000, 20.0);
        // ATR is ~20.7; create a tiny doji candle with range 2.0 (ratio ~0.10)
        Instant t = Instant.parse("2026-09-02T10:00:00Z");
        Candle dojiCandle = createCandle("NIFTY50", t.toString(), 24500, 24501, 24499, 24500.8);
        history.remove(history.size() - 1);

        TradeSignal signal = strategy.onCandle(dojiCandle, history);

        assertThat(signal.isActionable()).isFalse();
        assertThat(strategy.getActivePosition("NIFTY50")).isNull();
    }

    @Test
    void testVolumeFilterBlocksLowVolumeBreakout() {
        strategy.setVolumeFilterEnabled(true);
        strategy.setVolumeMultiplier(1.10);

        List<Candle> history = generateUptrendCandles("NIFTY50", 25, 24000, 20.0);
        // Create candle with near-zero volume (500 vs avg 50000)
        Instant t = Instant.parse("2026-09-02T10:00:00Z");
        Candle lowVolCandle =
                new Candle(
                        "NIFTY50",
                        "60",
                        t,
                        BigDecimal.valueOf(24480),
                        BigDecimal.valueOf(24505),
                        BigDecimal.valueOf(24480),
                        BigDecimal.valueOf(24500),
                        500);
        history.remove(history.size() - 1);

        TradeSignal signal = strategy.onCandle(lowVolCandle, history);

        assertThat(signal.isActionable()).isFalse();
        assertThat(strategy.getActivePosition("NIFTY50")).isNull();
    }

    @Test
    void testStagnationExitTriggersOnFlatPosition() {
        strategy.setMode("OPTION_BUYING");
        strategy.setStagnationExitEnabled(true);
        strategy.setStagnationBarsThreshold(4);

        List<Candle> history = generateUptrendCandles("NIFTY50", 25, 24000, 20.0);
        Candle latest = history.remove(history.size() - 1);
        strategy.onCandle(latest, history);
        assertThat(strategy.getActivePosition("NIFTY50")).isNotNull();

        // Feed a candle 5 hours later where underlying price did not advance
        Candle stagnantCandle =
                createCandle(
                        "NIFTY50",
                        latest.timestamp().plusSeconds(5 * 3600).toString(),
                        latest.close().doubleValue() - 10.0,
                        latest.close().doubleValue() + 5.0,
                        latest.close().doubleValue() - 15.0,
                        latest.close().doubleValue() - 5.0);

        TradeSignal exitSig = strategy.onCandle(stagnantCandle, history);
        assertThat(strategy.getActivePosition("NIFTY50")).isNull();
        assertThat(exitSig.action()).isEqualTo(SignalAction.EXIT_LONG);
        assertThat(exitSig.reason()).contains("Time-Decay Stagnation Stop");
    }
}
