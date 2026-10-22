package com.tradingbot.strategy.ttcstraddle.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradingbot.bus.SignalPublisher;
import com.tradingbot.marketdata.ShoonyaMarketDataService;
import com.tradingbot.strategy.ttcstraddle.config.IntradayStraddleProperties;
import com.tradingbot.strategy.ttcstraddle.model.StraddleSessionPhase;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class IntradayStraddleServiceTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private IntradayStraddleProperties properties;
    private ShoonyaMarketDataService marketDataService;
    private SignalPublisher signalPublisher;
    private IntradayStraddleService service;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        properties = new IntradayStraddleProperties();
        properties.setLots(1);
        properties.setMaxDailyLoss(8000.0);
        properties.setSlPercentage(50.0);
        properties.setThursdayEnabled(false);

        marketDataService = mock(ShoonyaMarketDataService.class);
        signalPublisher = mock(SignalPublisher.class);
        when(signalPublisher.publish(any())).thenReturn(true);

        mapper = new ObjectMapper();

        service = new IntradayStraddleService(properties, marketDataService, signalPublisher, null);
    }

    @Test
    @DisplayName("09:16 buys protection wings for NIFTY on Monday (1-DTE)")
    void testHedgeWingEntryMonday() {
        // Monday 2026-10-19 09:16 IST
        LocalDate monday = LocalDate.of(2026, 10, 19);
        service.setClock(Clock.fixed(Instant.parse("2026-10-19T03:46:00Z"), IST));
        service.resetDaily(monday);

        // Mock spot price 23,000
        ObjectNode spotQuote = mapper.createObjectNode().put("lp", "23000.00");
        when(marketDataService.fetchQuote("NSE", "26000")).thenReturn(spotQuote);

        service.executeHedgeWingEntry(LocalTime.of(9, 16));

        assertThat(service.getState().getPhase()).isEqualTo(StraddleSessionPhase.HEDGES_BOUGHT);
        assertThat(service.getState().getHedgeLongCe()).isNotNull();
        assertThat(service.getState().getHedgeLongPe()).isNotNull();

        // 23,025 rounded to 50 step is 23,000. Wings are 20 strikes (1000 pts) out.
        assertThat(service.getState().getHedgeLongCe().getStrike()).isEqualByComparingTo("24000");
        assertThat(service.getState().getHedgeLongPe().getStrike()).isEqualByComparingTo("22000");
    }

    @Test
    @DisplayName("09:22 sells morning ATM straddle with 50% stop loss")
    void testMorningStraddleEntry() {
        LocalDate monday = LocalDate.of(2026, 10, 19);
        service.setClock(Clock.fixed(Instant.parse("2026-10-19T03:52:00Z"), IST));
        service.resetDaily(monday);

        ObjectNode spotQuote = mapper.createObjectNode().put("lp", "23000.00");
        when(marketDataService.fetchQuote("NSE", "26000")).thenReturn(spotQuote);

        // Transition to hedges bought first
        service.executeHedgeWingEntry(LocalTime.of(9, 16));

        // Sell morning straddle
        service.executeMorningStraddleEntry(LocalTime.of(9, 22));

        assertThat(service.getState().getPhase())
                .isEqualTo(StraddleSessionPhase.MORNING_STRADDLE_ACTIVE);
        assertThat(service.getState().getMorningShortCe()).isNotNull();
        assertThat(service.getState().getMorningShortPe()).isNotNull();

        // Verify SL is 50% higher than entry
        BigDecimal ceEntry = service.getState().getMorningShortCe().getEntryPrice();
        BigDecimal ceSl = service.getState().getMorningShortCe().getStopLossPrice();
        assertThat(ceSl).isEqualByComparingTo(ceEntry.multiply(BigDecimal.valueOf(1.50)));
    }

    @Test
    @DisplayName("10:16 squares off morning short straddle while keeping hedges open")
    void testMorningSquareOffPreservesHedges() {
        LocalDate monday = LocalDate.of(2026, 10, 19);
        service.setClock(Clock.fixed(Instant.parse("2026-10-19T03:52:00Z"), IST));
        service.resetDaily(monday);

        ObjectNode spotQuote = mapper.createObjectNode().put("lp", "23000.00");
        when(marketDataService.fetchQuote("NSE", "26000")).thenReturn(spotQuote);

        service.executeHedgeWingEntry(LocalTime.of(9, 16));
        service.executeMorningStraddleEntry(LocalTime.of(9, 22));

        // Execute 10:16 square off
        service.executeMorningStraddleExit(LocalTime.of(10, 16));

        assertThat(service.getState().getPhase())
                .isEqualTo(StraddleSessionPhase.MORNING_STRADDLE_EXITED);
        assertThat(service.getState().getMorningShortCe().isActive()).isFalse();
        assertThat(service.getState().getMorningShortPe().isActive()).isFalse();

        // Hedges must remain active
        assertThat(service.getState().getHedgeLongCe().isActive()).isTrue();
        assertThat(service.getState().getHedgeLongPe().isActive()).isTrue();
    }

    @Test
    @DisplayName("50% SL breach exits the tested leg immediately without closing surviving leg")
    void testStopLossBreachExitsSingleLeg() {
        LocalDate monday = LocalDate.of(2026, 10, 19);
        service.setClock(Clock.fixed(Instant.parse("2026-10-19T03:52:00Z"), IST));
        service.resetDaily(monday);

        ObjectNode spotQuote = mapper.createObjectNode().put("lp", "23000.00");
        when(marketDataService.fetchQuote("NSE", "26000")).thenReturn(spotQuote);

        service.executeHedgeWingEntry(LocalTime.of(9, 16));
        service.executeMorningStraddleEntry(LocalTime.of(9, 22));

        // Mock Call price spiking to 151.0 (breaching SL 150.0), while Put price drops to 50.0
        BigDecimal ceSl = service.getState().getMorningShortCe().getStopLossPrice();
        ObjectNode spikedCallQuote =
                mapper.createObjectNode().put("lp", ceSl.add(BigDecimal.ONE).toString());
        ObjectNode decayingPutQuote = mapper.createObjectNode().put("lp", "50.00");

        String ceSymbol = service.getState().getMorningShortCe().getTradingSymbol();
        String peSymbol = service.getState().getMorningShortPe().getTradingSymbol();

        when(marketDataService.resolveToken(ceSymbol)).thenReturn("CALL_TOKEN");
        when(marketDataService.resolveToken(peSymbol)).thenReturn("PUT_TOKEN");
        when(marketDataService.fetchQuote("NFO", "CALL_TOKEN")).thenReturn(spikedCallQuote);
        when(marketDataService.fetchQuote("NFO", "PUT_TOKEN")).thenReturn(decayingPutQuote);

        service.evaluateStopLossBreaches(LocalTime.of(9, 45));

        // Short Call should be inactive, Put should still be active
        assertThat(service.getState().getMorningShortCe().isActive()).isFalse();
        assertThat(service.getState().getMorningShortPe().isActive()).isTrue();
    }

    @Test
    @DisplayName("15:35 final hard exit squares off all remaining legs completely")
    void testFinalHardExit() {
        LocalDate monday = LocalDate.of(2026, 10, 19);
        service.setClock(Clock.fixed(Instant.parse("2026-10-19T08:47:00Z"), IST)); // 14:17 IST
        service.resetDaily(monday);

        ObjectNode spotQuote = mapper.createObjectNode().put("lp", "23000.00");
        when(marketDataService.fetchQuote("NSE", "26000")).thenReturn(spotQuote);

        service.executeHedgeWingEntry(LocalTime.of(9, 16));
        service.executeMorningStraddleEntry(LocalTime.of(9, 22));
        service.executeMorningStraddleExit(LocalTime.of(10, 16));
        service.executeAfternoonStraddleEntry(LocalTime.of(14, 17));

        // Execute final hard exit
        service.executeHardSquareOff("15:35 EOD Hard Exit");

        assertThat(service.getState().getPhase()).isEqualTo(StraddleSessionPhase.ALL_CLOSED);
        assertThat(service.getState().getHedgeLongCe().isActive()).isFalse();
        assertThat(service.getState().getHedgeLongPe().isActive()).isFalse();
        assertThat(service.getState().getAfternoonShortCe().isActive()).isFalse();
        assertThat(service.getState().getAfternoonShortPe().isActive()).isFalse();
    }
}
