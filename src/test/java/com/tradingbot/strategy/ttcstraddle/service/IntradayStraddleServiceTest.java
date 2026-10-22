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
        properties.setSlPercentage(30.0);
        properties.setThursdayEnabled(true);

        marketDataService = mock(ShoonyaMarketDataService.class);
        signalPublisher = mock(SignalPublisher.class);
        when(signalPublisher.publish(any())).thenReturn(true);

        mapper = new ObjectMapper();

        service = new IntradayStraddleService(properties, marketDataService, signalPublisher, null);
    }

    @Test
    @DisplayName("09:45 buys 20-strikes OTM protection wings for NIFTY on Monday (1-DTE)")
    void testHedgeWingEntryMonday() {
        // Monday 2026-10-19 09:45 IST
        LocalDate monday = LocalDate.of(2026, 10, 19);
        service.setClock(Clock.fixed(Instant.parse("2026-10-19T04:15:00Z"), IST)); // 09:45 IST
        service.resetDaily(monday);

        ObjectNode spotQuote = mapper.createObjectNode().put("lp", "23000.00");
        when(marketDataService.fetchQuote("NSE", "26000")).thenReturn(spotQuote);

        service.executeHedgeWingEntry(LocalTime.of(9, 45));

        assertThat(service.getState().getPhase()).isEqualTo(StraddleSessionPhase.HEDGES_BOUGHT);
        assertThat(service.getState().getHedgeLongCe()).isNotNull();
        assertThat(service.getState().getHedgeLongPe()).isNotNull();

        // 23,000 spot: 20 strikes (1000 pts) out -> 24000 CE and 22000 PE
        assertThat(service.getState().getHedgeLongCe().getStrike()).isEqualByComparingTo("24000");
        assertThat(service.getState().getHedgeLongPe().getStrike()).isEqualByComparingTo("22000");
    }

    @Test
    @DisplayName("10:00 sells ATM straddle with 30% stop loss")
    void testStraddleEntry10am() {
        LocalDate monday = LocalDate.of(2026, 10, 19);
        service.setClock(Clock.fixed(Instant.parse("2026-10-19T04:30:00Z"), IST)); // 10:00 IST
        service.resetDaily(monday);

        ObjectNode spotQuote = mapper.createObjectNode().put("lp", "23000.00");
        when(marketDataService.fetchQuote("NSE", "26000")).thenReturn(spotQuote);

        service.executeHedgeWingEntry(LocalTime.of(9, 45));
        service.executeStraddleEntry(LocalTime.of(10, 0));

        assertThat(service.getState().getPhase()).isEqualTo(StraddleSessionPhase.STRADDLE_ACTIVE);
        assertThat(service.getState().getShortCe()).isNotNull();
        assertThat(service.getState().getShortPe()).isNotNull();

        // Verify SL is 30% higher than entry
        BigDecimal ceEntry = service.getState().getShortCe().getEntryPrice();
        BigDecimal ceSl = service.getState().getShortCe().getStopLossPrice();
        assertThat(ceSl).isEqualByComparingTo(ceEntry.multiply(BigDecimal.valueOf(1.30)));
    }

    @Test
    @DisplayName(
            "30% SL breach exits the tested leg immediately while surviving leg continues running")
    void testStopLossBreachExitsSingleLeg() {
        LocalDate monday = LocalDate.of(2026, 10, 19);
        service.setClock(Clock.fixed(Instant.parse("2026-10-19T04:30:00Z"), IST));
        service.resetDaily(monday);

        ObjectNode spotQuote = mapper.createObjectNode().put("lp", "23000.00");
        when(marketDataService.fetchQuote("NSE", "26000")).thenReturn(spotQuote);

        service.executeHedgeWingEntry(LocalTime.of(9, 45));
        service.executeStraddleEntry(LocalTime.of(10, 0));

        // Mock Call price spiking to 131.0 (breaching SL 130.0), while Put price drops to 50.0
        BigDecimal ceSl = service.getState().getShortCe().getStopLossPrice();
        ObjectNode spikedCallQuote =
                mapper.createObjectNode().put("lp", ceSl.add(BigDecimal.ONE).toString());
        ObjectNode decayingPutQuote = mapper.createObjectNode().put("lp", "50.00");

        String ceSymbol = service.getState().getShortCe().getTradingSymbol();
        String peSymbol = service.getState().getShortPe().getTradingSymbol();

        when(marketDataService.resolveToken(ceSymbol)).thenReturn("CALL_TOKEN");
        when(marketDataService.resolveToken(peSymbol)).thenReturn("PUT_TOKEN");
        when(marketDataService.fetchQuote("NFO", "CALL_TOKEN")).thenReturn(spikedCallQuote);
        when(marketDataService.fetchQuote("NFO", "PUT_TOKEN")).thenReturn(decayingPutQuote);

        service.evaluateStopLossBreaches(LocalTime.of(10, 45));

        // Short Call should be inactive (exited at 30% SL), Short Put must remain active
        assertThat(service.getState().getShortCe().isActive()).isFalse();
        assertThat(service.getState().getShortPe().isActive()).isTrue();
    }

    @Test
    @DisplayName("If other leg premium ALSO increases by 30%, it exits that leg as well")
    void testDoubleStopLossBreachExitsBothLegs() {
        LocalDate monday = LocalDate.of(2026, 10, 19);
        service.setClock(Clock.fixed(Instant.parse("2026-10-19T04:30:00Z"), IST));
        service.resetDaily(monday);

        ObjectNode spotQuote = mapper.createObjectNode().put("lp", "23000.00");
        when(marketDataService.fetchQuote("NSE", "26000")).thenReturn(spotQuote);

        service.executeHedgeWingEntry(LocalTime.of(9, 45));
        service.executeStraddleEntry(LocalTime.of(10, 0));

        // Mock market whipsaw: both Call and Put spiked above their 30% SLs
        BigDecimal ceSl = service.getState().getShortCe().getStopLossPrice();
        BigDecimal peSl = service.getState().getShortPe().getStopLossPrice();

        ObjectNode spikedCallQuote =
                mapper.createObjectNode().put("lp", ceSl.add(BigDecimal.ONE).toString());
        ObjectNode spikedPutQuote =
                mapper.createObjectNode().put("lp", peSl.add(BigDecimal.ONE).toString());

        String ceSymbol = service.getState().getShortCe().getTradingSymbol();
        String peSymbol = service.getState().getShortPe().getTradingSymbol();

        when(marketDataService.resolveToken(ceSymbol)).thenReturn("CALL_TOKEN");
        when(marketDataService.resolveToken(peSymbol)).thenReturn("PUT_TOKEN");
        when(marketDataService.fetchQuote("NFO", "CALL_TOKEN")).thenReturn(spikedCallQuote);
        when(marketDataService.fetchQuote("NFO", "PUT_TOKEN")).thenReturn(spikedPutQuote);

        service.evaluateStopLossBreaches(LocalTime.of(11, 15));

        // Both legs should be exited
        assertThat(service.getState().getShortCe().isActive()).isFalse();
        assertThat(service.getState().getShortPe().isActive()).isFalse();
    }

    @Test
    @DisplayName("15:00 PM hard exit squares off surviving short leg and hedges completely")
    void testFinalHardExitAt1500() {
        LocalDate monday = LocalDate.of(2026, 10, 19);
        service.setClock(Clock.fixed(Instant.parse("2026-10-19T04:30:00Z"), IST));
        service.resetDaily(monday);

        ObjectNode spotQuote = mapper.createObjectNode().put("lp", "23000.00");
        when(marketDataService.fetchQuote("NSE", "26000")).thenReturn(spotQuote);

        service.executeHedgeWingEntry(LocalTime.of(9, 45));
        service.executeStraddleEntry(LocalTime.of(10, 0));

        // Execute 15:00 hard exit
        service.executeHardSquareOff("15:00 PM EOD Hard Exit");

        assertThat(service.getState().getPhase()).isEqualTo(StraddleSessionPhase.ALL_CLOSED);
        assertThat(service.getState().getHedgeLongCe().isActive()).isFalse();
        assertThat(service.getState().getHedgeLongPe().isActive()).isFalse();
        assertThat(service.getState().getShortCe().isActive()).isFalse();
        assertThat(service.getState().getShortPe().isActive()).isFalse();
    }
}
