package com.tradingbot.strategy.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.tradingbot.indicator.TechnicalAnalysisService;
import com.tradingbot.marketdata.ShoonyaOptionChainService;
import com.tradingbot.model.Candle;
import com.tradingbot.model.OptionChainResponse;
import com.tradingbot.model.OptionContract;
import com.tradingbot.model.OptionStrike;
import com.tradingbot.strategy.SignalAction;
import com.tradingbot.strategy.TradeSignal;
import com.tradingbot.telegram.TelegramService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PivotSuperTrendOptionSellingStrategyTest {

    private TechnicalAnalysisService taService;
    private PivotSuperTrendOptionSellingStrategy strategy;

    @BeforeEach
    void setUp() {
        taService = new TechnicalAnalysisService();
        strategy = new PivotSuperTrendOptionSellingStrategy(taService);
        strategy.onResetDaily();
    }

    private Candle createCandle(
            String timeStr, double open, double high, double low, double close, long vol) {
        return new Candle(
                "NIFTY50",
                "5",
                Instant.parse(timeStr),
                BigDecimal.valueOf(open),
                BigDecimal.valueOf(high),
                BigDecimal.valueOf(low),
                BigDecimal.valueOf(close),
                vol);
    }

    private List<Candle> generatePrevDayHistory() {
        // Generate previous day bars (2026-09-03) with High=24100, Low=23900, Close=24000
        // Pivot P = (24100 + 23900 + 24000) / 3 = 24000.0
        // R1 = 2*24000 - 23900 = 24100.0
        // S1 = 2*24000 - 24100 = 23900.0
        List<Candle> history = new ArrayList<>();
        Instant start = Instant.parse("2026-09-03T03:45:00Z"); // 09:15 IST
        for (int i = 0; i < 20; i++) {
            Instant t = start.plusSeconds(i * 300);
            history.add(createCandle(t.toString(), 24000, 24100, 23900, 24000, 10000));
        }
        return history;
    }

    @Test
    void testDailyPivotCalculation() {
        List<Candle> prevDay = generatePrevDayHistory();
        Candle currentBar = createCandle("2026-09-04T03:45:00Z", 24050, 24060, 24040, 24050, 5000);

        strategy.onCandle(currentBar, prevDay);

        assertThat(strategy.getPivotPoint()).isEqualTo(24000.0);
        assertThat(strategy.getR1Level()).isEqualTo(24100.0);
        assertThat(strategy.getS1Level()).isEqualTo(23900.0);
    }

    @Test
    void testBullishEntryTriggersShortAtmPeWhenCloseAboveStAndR1() {
        // Set manual pivots: P=24000, R1=24050, S1=23950
        strategy.setManualPivots(24000.0, 24050.0, 23950.0);
        // Put OI (12M) - Call OI (6M) = 6M >= 5M (50 Lakhs threshold)
        strategy.setManualOtmOi(6_000_000L, 12_000_000L);

        // Build 15 bars of rising prices so SuperTrend(7, 3) is Bullish and below close
        List<Candle> history = new ArrayList<>();
        Instant start = Instant.parse("2026-09-04T03:45:00Z");
        for (int i = 0; i < 15; i++) {
            Instant t = start.plusSeconds(i * 300);
            history.add(
                    createCandle(
                            t.toString(),
                            24000 + i * 5,
                            24010 + i * 5,
                            23990 + i * 5,
                            24005 + i * 5,
                            10000));
        }

        // 16th bar closes at 24080 (> R1 24050 and > SuperTrend)
        Candle triggerBar = createCandle("2026-09-04T05:00:00Z", 24070, 24090, 24065, 24080, 25000);
        TradeSignal signal = strategy.onCandle(triggerBar, history);

        assertThat(signal.action()).isEqualTo(SignalAction.SELL);
        assertThat(signal.symbol()).contains("NIFTY_SHORT_PE");
        assertThat(signal.quantity()).isEqualTo(1);
        assertThat(signal.metadata().get("lots")).isEqualTo(1);
        assertThat(signal.metadata().get("quantity")).isEqualTo(65);
        assertThat(signal.metadata().get("buyHedge")).isEqualTo(true);
        assertThat(signal.metadata().get("hedgeDistance")).isEqualTo(150);
        assertThat(signal.metadata().get("hedgeStrike")).isEqualTo(BigDecimal.valueOf(23950));
        assertThat(signal.metadata().get("otmCallOi")).isEqualTo(6_000_000L);
        assertThat(signal.metadata().get("otmPutOi")).isEqualTo(12_000_000L);
        assertThat(signal.metadata().get("oiDifference")).isEqualTo(6_000_000L);
        assertThat(strategy.isInPosition()).isTrue();
        assertThat(strategy.getPositionType())
                .isEqualTo(PivotSuperTrendOptionSellingStrategy.PositionType.SHORT_PE);
        assertThat(strategy.getDailyTradesCount()).isEqualTo(1);
    }

    @Test
    void testBearishEntryTriggersShortAtmCeWhenCloseBelowStAndS1() {
        // Set manual pivots: P=24000, R1=24050, S1=23950
        strategy.setManualPivots(24000.0, 24050.0, 23950.0);
        // Call OI (12M) - Put OI (6M) = 6M >= 5M (50 Lakhs threshold)
        strategy.setManualOtmOi(12_000_000L, 6_000_000L);

        // Build 15 bars of falling prices so SuperTrend(7, 3) is Bearish and above close
        List<Candle> history = new ArrayList<>();
        Instant start = Instant.parse("2026-09-04T03:45:00Z");
        for (int i = 0; i < 15; i++) {
            Instant t = start.plusSeconds(i * 300);
            history.add(
                    createCandle(
                            t.toString(),
                            24000 - i * 5,
                            24005 - i * 5,
                            23980 - i * 5,
                            23985 - i * 5,
                            10000));
        }

        // 16th bar closes at 23920 (< S1 23950 and < SuperTrend)
        Candle triggerBar = createCandle("2026-09-04T05:00:00Z", 23930, 23935, 23910, 23920, 25000);
        TradeSignal signal = strategy.onCandle(triggerBar, history);

        assertThat(signal.action()).isEqualTo(SignalAction.SELL);
        assertThat(signal.symbol()).contains("NIFTY_SHORT_CE");
        assertThat(signal.quantity()).isEqualTo(1);
        assertThat(signal.metadata().get("lots")).isEqualTo(1);
        assertThat(signal.metadata().get("quantity")).isEqualTo(65);
        assertThat(signal.metadata().get("buyHedge")).isEqualTo(true);
        assertThat(signal.metadata().get("hedgeDistance")).isEqualTo(150);
        assertThat(signal.metadata().get("hedgeStrike")).isEqualTo(BigDecimal.valueOf(24050));
        assertThat(signal.metadata().get("otmCallOi")).isEqualTo(12_000_000L);
        assertThat(signal.metadata().get("otmPutOi")).isEqualTo(6_000_000L);
        assertThat(signal.metadata().get("oiDifference")).isEqualTo(6_000_000L);
        assertThat(strategy.isInPosition()).isTrue();
        assertThat(strategy.getPositionType())
                .isEqualTo(PivotSuperTrendOptionSellingStrategy.PositionType.SHORT_CE);
        assertThat(strategy.getDailyTradesCount()).isEqualTo(1);
    }

    @Test
    void testSuperTrendFlipExitsShortPePosition() {
        testBullishEntryTriggersShortAtmPeWhenCloseAboveStAndR1();
        assertThat(strategy.isInPosition()).isTrue();
        strategy.setStopLossEnabled(false); // Focus specifically on SuperTrend flip exit condition

        // While in Short PE, prices plummet sharply so SuperTrend flips Bearish
        List<Candle> history = new ArrayList<>();
        Instant start = Instant.parse("2026-09-04T05:05:00Z");
        for (int i = 0; i < 10; i++) {
            Instant t = start.plusSeconds(i * 300);
            history.add(
                    createCandle(
                            t.toString(),
                            24080 - i * 15,
                            24085 - i * 15,
                            24050 - i * 15,
                            24055 - i * 15,
                            20000));
        }

        Candle flipBar = createCandle("2026-09-04T05:55:00Z", 23920, 23925, 23890, 23900, 50000);
        TradeSignal exitSignal = strategy.onCandle(flipBar, history);

        assertThat(exitSignal.action()).isEqualTo(SignalAction.EXIT_SHORT);
        assertThat(exitSignal.quantity()).isEqualTo(1);
        assertThat(exitSignal.reason()).contains("SuperTrend flipped Bearish");
        assertThat(strategy.isInPosition()).isFalse();
    }

    @Test
    void testMandatory1514SquareOff() {
        testBullishEntryTriggersShortAtmPeWhenCloseAboveStAndR1();
        assertThat(strategy.isInPosition()).isTrue();

        // 15:14 IST (09:44 UTC)
        Candle cutoffBar = createCandle("2026-09-04T09:44:00Z", 24090, 24100, 24085, 24095, 5000);
        TradeSignal squareOff = strategy.onCandle(cutoffBar, List.of());

        assertThat(squareOff.action()).isEqualTo(SignalAction.SQUARE_OFF);
        assertThat(squareOff.quantity()).isEqualTo(1);
        assertThat(squareOff.reason()).contains("Mandatory 15:14 IST Square-Off");
        assertThat(strategy.isInPosition()).isFalse();
    }

    @Test
    void testMaxDailyTradesLimitEnforcedToOneTradePerDay() {
        // Run 1st trade: entry and flip exit
        testSuperTrendFlipExitsShortPePosition();
        assertThat(strategy.isInPosition()).isFalse();
        assertThat(strategy.getDailyTradesCount()).isEqualTo(1);

        // Attempt second entry on same day: even though price is above R1 and SuperTrend is bullish
        List<Candle> history = new ArrayList<>();
        Instant start = Instant.parse("2026-09-04T06:00:00Z");
        for (int i = 0; i < 15; i++) {
            Instant t = start.plusSeconds(i * 300);
            history.add(
                    createCandle(
                            t.toString(),
                            24100 + i * 5,
                            24110 + i * 5,
                            24090 + i * 5,
                            24105 + i * 5,
                            10000));
        }
        Candle secondTrigger =
                createCandle("2026-09-04T07:15:00Z", 24180, 24200, 24175, 24190, 25000);
        TradeSignal signal = strategy.onCandle(secondTrigger, history);

        assertThat(signal.action()).isEqualTo(SignalAction.HOLD);
        assertThat(signal.reason()).contains("Max daily trades (1) reached");
        assertThat(strategy.isInPosition()).isFalse();
        assertThat(strategy.getDailyTradesCount()).isEqualTo(1);
    }

    @Test
    void testTelegramAlertsDispatchedOnEntryAndExitWithStrikeAndPremium() {
        TelegramService mockTelegram = org.mockito.Mockito.mock(TelegramService.class);
        ShoonyaOptionChainService mockOptionChain =
                org.mockito.Mockito.mock(ShoonyaOptionChainService.class);

        // Mock option chain response
        OptionContract callContract =
                new OptionContract(
                        "NIFTY24050CE",
                        "token1",
                        "CE",
                        BigDecimal.valueOf(24100),
                        BigDecimal.valueOf(175.50),
                        10000L,
                        5000L,
                        BigDecimal.valueOf(175.0),
                        BigDecimal.valueOf(176.0),
                        BigDecimal.valueOf(170.0));
        OptionContract putContract =
                new OptionContract(
                        "NIFTY24050PE",
                        "token2",
                        "PE",
                        BigDecimal.valueOf(24100),
                        BigDecimal.valueOf(142.25),
                        12000L,
                        6000L,
                        BigDecimal.valueOf(142.0),
                        BigDecimal.valueOf(142.5),
                        BigDecimal.valueOf(140.0));
        OptionStrike strike24100 =
                new OptionStrike(BigDecimal.valueOf(24100), true, callContract, putContract);
        OptionChainResponse mockChain =
                new OptionChainResponse(
                        "NIFTY",
                        BigDecimal.valueOf(24080),
                        BigDecimal.valueOf(24100),
                        "NIFTY29SEP26F",
                        1,
                        10000L,
                        12000L,
                        1.2,
                        List.of(strike24100));

        org.mockito.Mockito.when(
                        mockOptionChain.getNifty50OptionChain(
                                org.mockito.ArgumentMatchers.any(),
                                org.mockito.ArgumentMatchers.anyInt(),
                                org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn(mockChain);

        PivotSuperTrendOptionSellingStrategy stratWithTelegram =
                new PivotSuperTrendOptionSellingStrategy(taService, mockOptionChain, mockTelegram);
        stratWithTelegram.setManualPivots(24000.0, 24050.0, 23950.0);
        stratWithTelegram.setManualOtmOi(6_000_000L, 12_000_000L);

        List<Candle> history = new ArrayList<>();
        Instant start = Instant.parse("2026-09-04T03:45:00Z");
        for (int i = 0; i < 15; i++) {
            Instant t = start.plusSeconds(i * 300);
            history.add(
                    createCandle(
                            t.toString(),
                            24000 + i * 5,
                            24010 + i * 5,
                            23990 + i * 5,
                            24005 + i * 5,
                            10000));
        }

        // 1. Bullish Entry Candle (Close 24080 > R1 24050)
        Candle triggerBar = createCandle("2026-09-04T05:00:00Z", 24070, 24090, 24065, 24080, 25000);
        TradeSignal signal = stratWithTelegram.onCandle(triggerBar, history);

        assertThat(signal.action()).isEqualTo(SignalAction.SELL);
        assertThat(signal.metadata()).containsKey("entryPrice");
        assertThat(signal.metadata()).containsKey("entryPremium");
        assertThat(signal.metadata().get("entryPremium")).isEqualTo(BigDecimal.valueOf(142.25));

        // Verify Telegram Entry Alert dispatched with strategy name, entry price, strike, and
        // premium
        org.mockito.Mockito.verify(mockTelegram)
                .sendStrategySignalAlert(
                        org.mockito.ArgumentMatchers.eq(
                                PivotSuperTrendOptionSellingStrategy.STRATEGY_NAME),
                        org.mockito.ArgumentMatchers.eq("NIFTY 50"),
                        org.mockito.ArgumentMatchers.contains("PE"),
                        org.mockito.ArgumentMatchers.eq(BigDecimal.valueOf(24080.0)),
                        org.mockito.ArgumentMatchers.eq(BigDecimal.valueOf(24100)),
                        org.mockito.ArgumentMatchers.eq("PE"),
                        org.mockito.ArgumentMatchers.eq(BigDecimal.valueOf(142.25)),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.eq(triggerBar.timestamp()));

        // 2. SuperTrend Flip Bearish Candle -> Exit
        List<Candle> dropHistory = new ArrayList<>();
        Instant dropStart = Instant.parse("2026-09-04T05:05:00Z");
        for (int i = 0; i < 10; i++) {
            Instant t = dropStart.plusSeconds(i * 300);
            dropHistory.add(
                    createCandle(
                            t.toString(),
                            24080 - i * 15,
                            24085 - i * 15,
                            24050 - i * 15,
                            24055 - i * 15,
                            20000));
        }
        Candle flipBar = createCandle("2026-09-04T05:55:00Z", 23920, 23925, 23890, 23900, 50000);
        TradeSignal exitSignal = stratWithTelegram.onCandle(flipBar, dropHistory);

        assertThat(exitSignal.action()).isEqualTo(SignalAction.EXIT_SHORT);
        assertThat(exitSignal.metadata()).containsKey("strike");
        assertThat(exitSignal.metadata()).containsKey("exitPremium");

        // Verify Telegram Exit Alert dispatched with strategy name, strike, and exit premium
        org.mockito.Mockito.verify(mockTelegram)
                .sendStrategyExitAlert(
                        org.mockito.ArgumentMatchers.eq(
                                PivotSuperTrendOptionSellingStrategy.STRATEGY_NAME),
                        org.mockito.ArgumentMatchers.eq("NIFTY 50"),
                        org.mockito.ArgumentMatchers.eq("EXIT SHORT PE"),
                        org.mockito.ArgumentMatchers.eq(BigDecimal.valueOf(23900.0)),
                        org.mockito.ArgumentMatchers.eq(BigDecimal.valueOf(24100)),
                        org.mockito.ArgumentMatchers.eq("PE"),
                        org.mockito.ArgumentMatchers.eq(BigDecimal.valueOf(142.25)),
                        org.mockito.ArgumentMatchers.eq(BigDecimal.valueOf(142.25)),
                        org.mockito.ArgumentMatchers.contains("SuperTrend flipped Bearish"),
                        org.mockito.ArgumentMatchers.eq(flipBar.timestamp()));
    }

    @Test
    void testBullishEntryFilteredOutWhenPutOiDiffLessThan50Lakhs() {
        strategy.setOiFilterEnabled(true);
        strategy.setManualPivots(24000.0, 24050.0, 23950.0);
        // Put OI (12M) - Call OI (10M) = 2M < 5M (50 Lakhs threshold)
        strategy.setManualOtmOi(10_000_000L, 12_000_000L);

        List<Candle> history = new ArrayList<>();
        Instant start = Instant.parse("2026-09-04T03:45:00Z");
        for (int i = 0; i < 15; i++) {
            Instant t = start.plusSeconds(i * 300);
            history.add(
                    createCandle(
                            t.toString(),
                            24000 + i * 5,
                            24010 + i * 5,
                            23990 + i * 5,
                            24005 + i * 5,
                            10000));
        }

        Candle triggerBar = createCandle("2026-09-04T05:00:00Z", 24070, 24090, 24065, 24080, 25000);
        TradeSignal signal = strategy.onCandle(triggerBar, history);

        assertThat(signal.action()).isEqualTo(SignalAction.HOLD);
        assertThat(signal.reason()).contains("diff (2000000) < 5000000 threshold");
        assertThat(strategy.isInPosition()).isFalse();
    }

    @Test
    void testBearishEntryFilteredOutWhenCallOiDiffLessThan50Lakhs() {
        strategy.setOiFilterEnabled(true);
        strategy.setManualPivots(24000.0, 24050.0, 23950.0);
        // Call OI (12M) - Put OI (10M) = 2M < 5M (50 Lakhs threshold)
        strategy.setManualOtmOi(12_000_000L, 10_000_000L);

        List<Candle> history = new ArrayList<>();
        Instant start = Instant.parse("2026-09-04T03:45:00Z");
        for (int i = 0; i < 15; i++) {
            Instant t = start.plusSeconds(i * 300);
            history.add(
                    createCandle(
                            t.toString(),
                            24000 - i * 5,
                            24005 - i * 5,
                            23980 - i * 5,
                            23985 - i * 5,
                            10000));
        }

        Candle triggerBar = createCandle("2026-09-04T05:00:00Z", 23930, 23935, 23910, 23920, 25000);
        TradeSignal signal = strategy.onCandle(triggerBar, history);

        assertThat(signal.action()).isEqualTo(SignalAction.HOLD);
        assertThat(signal.reason()).contains("diff (2000000) < 5000000 threshold");
        assertThat(strategy.isInPosition()).isFalse();
    }

    @Test
    void testBullishEntryFilteredOutWhenOpposingCallOiExceedsPutOi() {
        strategy.setOiFilterEnabled(true);
        strategy.setManualPivots(24000.0, 24050.0, 23950.0);
        // Call OI (15M) > Put OI (8M) -> Opposing OI sentiment
        strategy.setManualOtmOi(15_000_000L, 8_000_000L);

        List<Candle> history = new ArrayList<>();
        Instant start = Instant.parse("2026-09-04T03:45:00Z");
        for (int i = 0; i < 15; i++) {
            Instant t = start.plusSeconds(i * 300);
            history.add(
                    createCandle(
                            t.toString(),
                            24000 + i * 5,
                            24010 + i * 5,
                            23990 + i * 5,
                            24005 + i * 5,
                            10000));
        }

        Candle triggerBar = createCandle("2026-09-04T05:00:00Z", 24070, 24090, 24065, 24080, 25000);
        TradeSignal signal = strategy.onCandle(triggerBar, history);

        assertThat(signal.action()).isEqualTo(SignalAction.HOLD);
        assertThat(signal.reason()).contains("diff (-7000000) < 5000000 threshold");
        assertThat(strategy.isInPosition()).isFalse();
    }

    @Test
    void testOptionChainCalculatesExact3OtmStrikesFromChain() {
        ShoonyaOptionChainService mockChainService =
                org.mockito.Mockito.mock(ShoonyaOptionChainService.class);
        PivotSuperTrendOptionSellingStrategy strat =
                new PivotSuperTrendOptionSellingStrategy(taService, mockChainService, null);
        strat.setOiFilterEnabled(true);
        strat.setManualPivots(24000.0, 24050.0, 23950.0);

        // ATM = 24100
        // 3 OTM Calls: 24150 (2.0M), 24200 (2.5M), 24250 (1.5M) => Sum = 6.0M
        // 3 OTM Puts:  24050 (4.0M), 24000 (4.5M), 23950 (3.0M) => Sum = 11.5M
        // Difference (Put - Call) = 5.5M >= 5M (55 Lakhs)
        OptionContract cAtm =
                new OptionContract(
                        "C_ATM",
                        "1",
                        "CE",
                        BigDecimal.valueOf(24100),
                        BigDecimal.valueOf(160.0),
                        1000L,
                        1000L,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO);
        OptionContract pAtm =
                new OptionContract(
                        "P_ATM",
                        "2",
                        "PE",
                        BigDecimal.valueOf(24100),
                        BigDecimal.valueOf(155.0),
                        1000L,
                        1000L,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO);
        OptionStrike sAtm = new OptionStrike(BigDecimal.valueOf(24100), true, cAtm, pAtm);

        OptionContract c1 =
                new OptionContract(
                        "C1",
                        "3",
                        "CE",
                        BigDecimal.valueOf(24150),
                        BigDecimal.valueOf(120.0),
                        2_000_000L,
                        1000L,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO);
        OptionStrike sc1 = new OptionStrike(BigDecimal.valueOf(24150), false, c1, null);

        OptionContract c2 =
                new OptionContract(
                        "C2",
                        "4",
                        "CE",
                        BigDecimal.valueOf(24200),
                        BigDecimal.valueOf(90.0),
                        2_500_000L,
                        1000L,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO);
        OptionStrike sc2 = new OptionStrike(BigDecimal.valueOf(24200), false, c2, null);

        OptionContract c3 =
                new OptionContract(
                        "C3",
                        "5",
                        "CE",
                        BigDecimal.valueOf(24250),
                        BigDecimal.valueOf(60.0),
                        1_500_000L,
                        1000L,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO);
        OptionStrike sc3 = new OptionStrike(BigDecimal.valueOf(24250), false, c3, null);

        OptionContract p1 =
                new OptionContract(
                        "P1",
                        "6",
                        "PE",
                        BigDecimal.valueOf(24050),
                        BigDecimal.valueOf(125.0),
                        4_000_000L,
                        1000L,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO);
        OptionStrike sp1 = new OptionStrike(BigDecimal.valueOf(24050), false, null, p1);

        OptionContract p2 =
                new OptionContract(
                        "P2",
                        "7",
                        "PE",
                        BigDecimal.valueOf(24000),
                        BigDecimal.valueOf(95.0),
                        4_500_000L,
                        1000L,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO);
        OptionStrike sp2 = new OptionStrike(BigDecimal.valueOf(24000), false, null, p2);

        OptionContract p3 =
                new OptionContract(
                        "P3",
                        "8",
                        "PE",
                        BigDecimal.valueOf(23950),
                        BigDecimal.valueOf(65.0),
                        3_000_000L,
                        1000L,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO);
        OptionStrike sp3 = new OptionStrike(BigDecimal.valueOf(23950), false, null, p3);

        OptionChainResponse response =
                new OptionChainResponse(
                        "NIFTY",
                        BigDecimal.valueOf(24080),
                        BigDecimal.valueOf(24100),
                        "NIFTY_FUT",
                        7,
                        6_000_000L,
                        11_500_000L,
                        1.9,
                        List.of(sp3, sp2, sp1, sAtm, sc1, sc2, sc3));
        org.mockito.Mockito.when(
                        mockChainService.getNifty50OptionChain(
                                org.mockito.ArgumentMatchers.any(),
                                org.mockito.ArgumentMatchers.anyInt(),
                                org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn(response);

        List<Candle> history = new ArrayList<>();
        Instant start = Instant.parse("2026-09-04T03:45:00Z");
        for (int i = 0; i < 15; i++) {
            Instant t = start.plusSeconds(i * 300);
            history.add(
                    createCandle(
                            t.toString(),
                            24000 + i * 5,
                            24010 + i * 5,
                            23990 + i * 5,
                            24005 + i * 5,
                            10000));
        }

        Candle triggerBar = createCandle("2026-09-04T05:00:00Z", 24070, 24090, 24065, 24080, 25000);
        TradeSignal signal = strat.onCandle(triggerBar, history);

        assertThat(signal.action()).isEqualTo(SignalAction.SELL);
        assertThat(signal.symbol()).contains("NIFTY_SHORT_PE");
        assertThat(signal.metadata().get("otmCallOi")).isEqualTo(6_000_000L);
        assertThat(signal.metadata().get("otmPutOi")).isEqualTo(11_500_000L);
        assertThat(signal.metadata().get("oiDifference")).isEqualTo(5_500_000L);
        assertThat(strat.isInPosition()).isTrue();
    }

    @Test
    void testMorningFilterPreventsEntryBefore0930Ist() {
        strategy.setManualPivots(24000.0, 24050.0, 23950.0);

        List<Candle> history = new ArrayList<>();
        // 03:45 UTC is 09:15 IST (Market Open)
        Instant openTime = Instant.parse("2026-09-04T03:45:00Z");
        history.add(createCandle(openTime.toString(), 24040, 24060, 24035, 24055, 10000));

        // 03:50 UTC is 09:20 IST (Before 09:30 IST)
        Candle earlyBar = createCandle("2026-09-04T03:50:00Z", 24060, 24085, 24055, 24080, 25000);
        TradeSignal signal = strategy.onCandle(earlyBar, history);

        assertThat(signal.action()).isEqualTo(SignalAction.HOLD);
        assertThat(signal.reason()).contains("Morning open filter");
        assertThat(strategy.isInPosition()).isFalse();
        assertThat(strategy.getDailyTradesCount()).isZero();
    }

    @Test
    void testHardStopLossTriggersAt30PercentWhenOptionPremiumExpands() {
        // Trigger a Bullish Entry (Short ATM PE @ 150.00)
        testBullishEntryTriggersShortAtmPeWhenCloseAboveStAndR1();
        assertThat(strategy.isInPosition()).isTrue();
        assertThat(strategy.getEntryPremium()).isEqualByComparingTo(BigDecimal.valueOf(150.00));

        // 30% Hard SL on 150.00 is 195.00
        // Price drops sharply against the Short PE:
        // Entry price = 24080. If price drops to 23970 (110 pts drop),
        // estCurrent = 150 + (110 * 0.50) = 205.00 >= 195.00 -> SL HIT!
        List<Candle> dropHistory = new ArrayList<>();
        Instant dropStart = Instant.parse("2026-09-04T05:05:00Z");
        for (int i = 0; i < 5; i++) {
            Instant t = dropStart.plusSeconds(i * 300);
            dropHistory.add(
                    createCandle(
                            t.toString(),
                            24080 - i * 15,
                            24085 - i * 15,
                            24050 - i * 15,
                            24060 - i * 15,
                            20000));
        }

        Candle slHitBar = createCandle("2026-09-04T05:30:00Z", 24000, 24010, 23960, 23970, 50000);
        TradeSignal slSignal = strategy.onCandle(slHitBar, dropHistory);

        assertThat(slSignal.action()).isEqualTo(SignalAction.EXIT_SHORT);
        assertThat(slSignal.reason()).contains("Hard Stop Loss Hit");
        assertThat(slSignal.metadata()).containsKey("stopLossPremium");
        assertThat(slSignal.metadata().get("stopLossPremium"))
                .isEqualTo(BigDecimal.valueOf(195.00).setScale(2));
        assertThat(strategy.isInPosition()).isFalse();
    }

    @Test
    void testTargetProfitBookingTriggersAt50PercentPremiumDecay() {
        // Trigger Bullish Entry (Short ATM PE @ 150.00)
        testBullishEntryTriggersShortAtmPeWhenCloseAboveStAndR1();
        assertThat(strategy.isInPosition()).isTrue();
        assertThat(strategy.getEntryPremium()).isEqualByComparingTo(BigDecimal.valueOf(150.00));

        // 50% target on 150.00 is 75.00
        // Price rallies further: Entry price = 24080. If price rallies to 24230 (+150 pts),
        // estCurrent = 150 - (150 * 0.50) = 75.00 <= 75.00 -> TARGET HIT!
        List<Candle> rallyHistory = new ArrayList<>();
        Instant rallyStart = Instant.parse("2026-09-04T05:05:00Z");
        for (int i = 0; i < 5; i++) {
            Instant t = rallyStart.plusSeconds(i * 300);
            rallyHistory.add(
                    createCandle(
                            t.toString(),
                            24080 + i * 20,
                            24095 + i * 20,
                            24075 + i * 20,
                            24090 + i * 20,
                            20000));
        }

        Candle targetBar = createCandle("2026-09-04T05:30:00Z", 24210, 24235, 24205, 24230, 45000);
        TradeSignal targetSignal = strategy.onCandle(targetBar, rallyHistory);

        assertThat(targetSignal.action()).isEqualTo(SignalAction.EXIT_SHORT);
        assertThat(targetSignal.reason()).contains("Take Profit Target Hit");
        assertThat(targetSignal.metadata()).containsKey("targetPremium");
        assertThat(targetSignal.metadata().get("targetPremium"))
                .isEqualTo(BigDecimal.valueOf(75.00).setScale(2));
        assertThat(strategy.isInPosition()).isFalse();
    }

    @Test
    void testCreditSpreadBullPutHedgeLegParameters() {
        strategy.setLots(1);
        strategy.setBuyHedge(true);
        strategy.setHedgeDistance(150);
        strategy.setManualPivots(24000.0, 24050.0, 23950.0);

        List<Candle> history = new ArrayList<>();
        Instant start = Instant.parse("2026-09-04T03:45:00Z");
        for (int i = 0; i < 15; i++) {
            Instant t = start.plusSeconds(i * 300);
            history.add(
                    createCandle(
                            t.toString(),
                            24000 + i * 5,
                            24010 + i * 5,
                            23990 + i * 5,
                            24005 + i * 5,
                            10000));
        }

        Candle triggerBar = createCandle("2026-09-04T05:00:00Z", 24070, 24090, 24065, 24080, 25000);
        TradeSignal signal = strategy.onCandle(triggerBar, history);

        assertThat(signal.action()).isEqualTo(SignalAction.SELL);
        assertThat(signal.quantity()).isEqualTo(1);
        assertThat(signal.reason()).contains("Bull Put Spread");
        assertThat(signal.metadata().get("buyHedge")).isEqualTo(true);
        assertThat(signal.metadata().get("hedgeDistance")).isEqualTo(150);
        assertThat(signal.metadata().get("atmStrike")).isEqualTo(BigDecimal.valueOf(24100));
        assertThat(signal.metadata().get("hedgeStrike")).isEqualTo(BigDecimal.valueOf(23950));
        assertThat(strategy.getHedgeStrike()).isEqualTo(BigDecimal.valueOf(23950));
        assertThat(strategy.getHedgeSymbol()).isEqualTo("NIFTY_BUY_PE_23950");
    }

    @Test
    void testDisableHedgeSpreadRevertsToNakedSelling() {
        strategy.setLots(2);
        strategy.setBuyHedge(false);
        strategy.setManualPivots(24000.0, 24050.0, 23950.0);

        List<Candle> history = new ArrayList<>();
        Instant start = Instant.parse("2026-09-04T03:45:00Z");
        for (int i = 0; i < 15; i++) {
            Instant t = start.plusSeconds(i * 300);
            history.add(
                    createCandle(
                            t.toString(),
                            24000 + i * 5,
                            24010 + i * 5,
                            23990 + i * 5,
                            24005 + i * 5,
                            10000));
        }

        Candle triggerBar = createCandle("2026-09-04T05:00:00Z", 24070, 24090, 24065, 24080, 25000);
        TradeSignal signal = strategy.onCandle(triggerBar, history);

        assertThat(signal.action()).isEqualTo(SignalAction.SELL);
        assertThat(signal.quantity()).isEqualTo(2);
        assertThat(signal.metadata().get("lots")).isEqualTo(2);
        assertThat(signal.metadata().get("quantity")).isEqualTo(130);
        assertThat(signal.metadata().get("buyHedge")).isEqualTo(false);
        assertThat(signal.metadata().get("hedgeStrike")).isEqualTo(BigDecimal.ZERO);
    }
}
