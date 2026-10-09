package com.tradingbot.strategy.commodity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradingbot.indicator.TechnicalAnalysisService;
import com.tradingbot.marketdata.ShoonyaMarketDataService;
import com.tradingbot.marketdata.ShoonyaOptionChainService;
import com.tradingbot.model.Candle;
import com.tradingbot.model.OptionChainResponse;
import com.tradingbot.strategy.commodity.config.CommodityVwapProperties;
import com.tradingbot.strategy.commodity.model.CommodityBias;
import com.tradingbot.strategy.commodity.model.CommoditySetup;
import com.tradingbot.strategy.commodity.model.CommoditySetupState;
import com.tradingbot.strategy.commodity.model.CommodityTradePosition;
import com.tradingbot.telegram.TelegramService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CommodityVwapStrategyServiceTest {

    private ShoonyaOptionChainService optionChainService;
    private ShoonyaMarketDataService marketDataService;
    private TechnicalAnalysisService taService;
    private TelegramService telegramService;
    private CommodityVwapProperties properties;
    private CommodityVwapStrategyService service;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        optionChainService = mock(ShoonyaOptionChainService.class);
        marketDataService = mock(ShoonyaMarketDataService.class);
        taService = mock(TechnicalAnalysisService.class);
        telegramService = mock(TelegramService.class);
        properties = new CommodityVwapProperties();
        properties.setSymbols(List.of("CRUDEOIL", "GOLD", "SILVER"));
        objectMapper = new ObjectMapper();

        service =
                new CommodityVwapStrategyService(
                        properties,
                        optionChainService,
                        marketDataService,
                        taService,
                        telegramService);
    }

    @Test
    @DisplayName("Should evaluate PCR and determine BULLISH, BEARISH, and NEUTRAL bias")
    void testEvaluateDailyBias() {
        // Bullish PCR for CRUDEOIL: Put OI = 13000, Call OI = 10000 -> PCR = 1.30 >= 1.15
        OptionChainResponse crudeChain = createMockChain("CRUDEOIL", 13000, 10000);
        when(optionChainService.getOptionChain(
                        eq("CRUDEOIL"), anyString(), anyString(), any(), anyInt(), anyBoolean()))
                .thenReturn(crudeChain);

        // Bearish PCR for GOLD: Put OI = 7000, Call OI = 10000 -> PCR = 0.70 <= 0.85
        OptionChainResponse goldChain = createMockChain("GOLD", 7000, 10000);
        when(optionChainService.getOptionChain(
                        eq("GOLD"), anyString(), anyString(), any(), anyInt(), anyBoolean()))
                .thenReturn(goldChain);

        // Neutral PCR for SILVER: Put OI = 10000, Call OI = 10000 -> PCR = 1.00 (between 0.85 and
        // 1.15)
        OptionChainResponse silverChain = createMockChain("SILVER", 10000, 10000);
        when(optionChainService.getOptionChain(
                        eq("SILVER"), anyString(), anyString(), any(), anyInt(), anyBoolean()))
                .thenReturn(silverChain);

        service.evaluateDailyBias();

        CommoditySetup crudeSetup = service.getSetup("CRUDEOIL");
        assertThat(crudeSetup.getBias()).isEqualTo(CommodityBias.BULLISH);
        assertThat(crudeSetup.getPcr()).isEqualTo(1.30);
        assertThat(crudeSetup.getState()).isEqualTo(CommoditySetupState.BIAS_IDENTIFIED);

        CommoditySetup goldSetup = service.getSetup("GOLD");
        assertThat(goldSetup.getBias()).isEqualTo(CommodityBias.BEARISH);
        assertThat(goldSetup.getPcr()).isEqualTo(0.70);
        assertThat(goldSetup.getState()).isEqualTo(CommoditySetupState.BIAS_IDENTIFIED);

        CommoditySetup silverSetup = service.getSetup("SILVER");
        assertThat(silverSetup.getBias()).isEqualTo(CommodityBias.NEUTRAL);
        assertThat(silverSetup.getPcr()).isEqualTo(1.00);
        assertThat(silverSetup.getState()).isEqualTo(CommoditySetupState.SKIPPED);

        verify(telegramService).sendTextMessage(contains("MCX Commodity Directional Bias"));
    }

    @Test
    @DisplayName("Should arm ARMED_LONG when Bullish and 15m candle crosses above VWAP")
    void testBullishVwapCrossover() {
        CommoditySetup setup = service.getSetup("CRUDEOIL");
        setup.updateBias(CommodityBias.BULLISH, 1.25);

        // Previous candle closed below VWAP (close=5480, vwap=5500)
        // Current candle crosses and closes above VWAP (close=5520, high=5530, vwap=5505)
        List<Candle> candles =
                List.of(
                        createCandle("CRUDEOIL", 5450, 5490, 5440, 5480, 100),
                        createCandle("CRUDEOIL", 5480, 5530, 5475, 5520, 200));

        when(marketDataService.fetch15MinCandles(eq("CRUDEOIL"), anyInt())).thenReturn(candles);
        when(taService.calculateVwapSeries(anyList())).thenReturn(new double[] {5500.0, 5505.0});

        service.evaluateSymbolCycle("CRUDEOIL", LocalTime.of(14, 0));

        assertThat(setup.getState()).isEqualTo(CommoditySetupState.ARMED_LONG);
        assertThat(setup.getTriggerHigh()).isEqualByComparingTo(new BigDecimal("5530.00"));
        assertThat(setup.getVwapAtSetup()).isEqualByComparingTo(new BigDecimal("5505.00"));
    }

    @Test
    @DisplayName("Should arm ARMED_SHORT when Bearish and 15m candle crosses below VWAP")
    void testBearishVwapCrossover() {
        CommoditySetup setup = service.getSetup("GOLD");
        setup.updateBias(CommodityBias.BEARISH, 0.75);

        // Previous candle closed above VWAP (close=75200, vwap=75100)
        // Current candle crosses and closes below VWAP (close=75050, low=75000, vwap=75100)
        List<Candle> candles =
                List.of(
                        createCandle("GOLD", 75000, 75250, 74950, 75200, 100),
                        createCandle("GOLD", 75200, 75210, 75000, 75050, 200));

        when(marketDataService.fetch15MinCandles(eq("GOLD"), anyInt())).thenReturn(candles);
        when(taService.calculateVwapSeries(anyList())).thenReturn(new double[] {75100.0, 75100.0});

        service.evaluateSymbolCycle("GOLD", LocalTime.of(14, 15));

        assertThat(setup.getState()).isEqualTo(CommoditySetupState.ARMED_SHORT);
        assertThat(setup.getTriggerLow()).isEqualByComparingTo(new BigDecimal("75000.00"));
        assertThat(setup.getVwapAtSetup()).isEqualByComparingTo(new BigDecimal("75100.00"));
    }

    @Test
    @DisplayName("Should execute LONG trade entry on trigger high breakout with 1:2 RR")
    void testLongTradeEntryAndExitOnTarget() {
        CommoditySetup setup = service.getSetup("CRUDEOIL");
        setup.updateBias(CommodityBias.BULLISH, 1.30);
        setup.armLong(
                new BigDecimal("5530.00"),
                new BigDecimal("5500.00"),
                Instant.now()); // Risk = 30 pts

        when(marketDataService.fetch15MinCandles(eq("CRUDEOIL"), anyInt()))
                .thenReturn(Collections.emptyList());

        // 1. Live quote breaks Trigger High (LTP = 5532 >= 5530)
        ObjectNode quoteNode = objectMapper.createObjectNode();
        quoteNode.put("lp", "5532.00");
        when(marketDataService.resolveToken(anyString())).thenReturn("12345");
        when(marketDataService.resolveExchange(anyString())).thenReturn("MCX");
        when(marketDataService.fetchQuote("MCX", "12345")).thenReturn(quoteNode);

        service.evaluateSymbolCycle("CRUDEOIL", LocalTime.of(14, 30));

        assertThat(setup.getState()).isEqualTo(CommoditySetupState.IN_TRADE);
        CommodityTradePosition pos = setup.getActivePosition();
        assertThat(pos).isNotNull();
        assertThat(pos.side()).isEqualTo("LONG");
        assertThat(pos.entryPrice()).isEqualByComparingTo(new BigDecimal("5530.00"));
        assertThat(pos.stopLoss()).isEqualByComparingTo(new BigDecimal("5500.00")); // VWAP
        assertThat(pos.targetPrice())
                .isEqualByComparingTo(new BigDecimal("5590.00")); // 5530 + 2*30

        // 2. Next check: Target is reached (LTP = 5595 >= 5590)
        quoteNode.put("lp", "5595.00");
        service.evaluateSymbolCycle("CRUDEOIL", LocalTime.of(15, 0));

        assertThat(setup.getState()).isEqualTo(CommoditySetupState.COMPLETED);
        assertThat(setup.getActivePosition().isClosed()).isTrue();
        assertThat(setup.getActivePosition().exitReason()).isEqualTo("TARGET_HIT");
        assertThat(setup.getTradesToday()).isEqualTo(1);
    }

    @Test
    @DisplayName("Should execute SHORT trade entry on trigger low breakout and exit on Stop Loss")
    void testShortTradeEntryAndExitOnStopLoss() {
        CommoditySetup setup = service.getSetup("GOLD");
        setup.updateBias(CommodityBias.BEARISH, 0.75);
        setup.armShort(
                new BigDecimal("75000.00"),
                new BigDecimal("75300.00"),
                Instant.now()); // Risk = 300 pts

        when(marketDataService.fetch15MinCandles(eq("GOLD"), anyInt()))
                .thenReturn(Collections.emptyList());

        // 1. Live quote breaks Trigger Low (LTP = 74990 <= 75000)
        ObjectNode quoteNode = objectMapper.createObjectNode();
        quoteNode.put("lp", "74990.00");
        when(marketDataService.resolveToken(anyString())).thenReturn("67890");
        when(marketDataService.resolveExchange(anyString())).thenReturn("MCX");
        when(marketDataService.fetchQuote("MCX", "67890")).thenReturn(quoteNode);

        service.evaluateSymbolCycle("GOLD", LocalTime.of(14, 30));

        assertThat(setup.getState()).isEqualTo(CommoditySetupState.IN_TRADE);
        CommodityTradePosition pos = setup.getActivePosition();
        assertThat(pos).isNotNull();
        assertThat(pos.side()).isEqualTo("SHORT");
        assertThat(pos.entryPrice()).isEqualByComparingTo(new BigDecimal("75000.00"));
        assertThat(pos.stopLoss()).isEqualByComparingTo(new BigDecimal("75300.00"));
        assertThat(pos.targetPrice())
                .isEqualByComparingTo(new BigDecimal("74400.00")); // 75000 - 2*300

        // 2. Stop Loss is hit (LTP = 75310 >= 75300)
        quoteNode.put("lp", "75310.00");
        service.evaluateSymbolCycle("GOLD", LocalTime.of(15, 0));

        assertThat(setup.getState()).isEqualTo(CommoditySetupState.COMPLETED);
        assertThat(setup.getActivePosition().isClosed()).isTrue();
        assertThat(setup.getActivePosition().exitReason()).isEqualTo("STOP_LOSS_HIT");
        assertThat(setup.getTradesToday()).isEqualTo(1);
    }

    @Test
    @DisplayName("Should enforce daily maximum trades and skip new setups if already completed")
    void testMaxTradesConstraint() {
        CommoditySetup setup = service.getSetup("SILVER");
        setup.updateBias(CommodityBias.BULLISH, 1.30);
        setup.setTradesToday(1);
        setup.setState(CommoditySetupState.COMPLETED);

        service.evaluateSymbolCycle("SILVER", LocalTime.of(16, 0));

        // State remains COMPLETED and does not re-arm
        assertThat(setup.getState()).isEqualTo(CommoditySetupState.COMPLETED);
        verify(marketDataService, never()).fetch15MinCandles(eq("SILVER"), anyInt());
    }

    @Test
    @DisplayName("Should square off all open positions at 23:15 EOD")
    void testEodSquareOff() {
        CommoditySetup setup = service.getSetup("CRUDEOIL");
        setup.updateBias(CommodityBias.BULLISH, 1.30);
        CommodityTradePosition pos =
                CommodityTradePosition.createLong(
                        "CRUDEOILM",
                        new BigDecimal("5500.00"),
                        new BigDecimal("5450.00"),
                        new BigDecimal("2.0"),
                        10,
                        Instant.now());
        setup.enterTrade(pos);

        ObjectNode quoteNode = objectMapper.createObjectNode();
        quoteNode.put("lp", "5540.00");
        when(marketDataService.resolveToken(anyString())).thenReturn("12345");
        when(marketDataService.resolveExchange(anyString())).thenReturn("MCX");
        when(marketDataService.fetchQuote("MCX", "12345")).thenReturn(quoteNode);

        service.squareOffAllPositions("EOD_SQUARE_OFF");

        assertThat(setup.getState()).isEqualTo(CommoditySetupState.COMPLETED);
        assertThat(setup.getActivePosition().isClosed()).isTrue();
        assertThat(setup.getActivePosition().exitReason()).isEqualTo("EOD_SQUARE_OFF");
        assertThat(setup.getActivePosition().exitPrice())
                .isEqualByComparingTo(new BigDecimal("5540.00"));
    }

    private OptionChainResponse createMockChain(String underlying, long putOi, long callOi) {
        double pcr = callOi > 0 ? (double) putOi / callOi : 0.0;
        double roundedPcr = Math.round(pcr * 100.0) / 100.0;
        return new OptionChainResponse(
                underlying,
                BigDecimal.valueOf(5000),
                BigDecimal.valueOf(5000),
                underlying + "-FUT",
                5,
                callOi,
                putOi,
                roundedPcr,
                Collections.emptyList());
    }

    private Candle createCandle(
            String symbol, double open, double high, double low, double close, long volume) {
        return new Candle(
                symbol,
                "15",
                Instant.now(),
                BigDecimal.valueOf(open),
                BigDecimal.valueOf(high),
                BigDecimal.valueOf(low),
                BigDecimal.valueOf(close),
                volume);
    }
}
