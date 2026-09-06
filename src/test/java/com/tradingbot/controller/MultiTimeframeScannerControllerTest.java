package com.tradingbot.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tradingbot.model.strategy.MtfTrendStatus;
import com.tradingbot.scheduler.HourlyMtfScannerScheduler;
import com.tradingbot.service.MultiTimeframeScannerService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

class MultiTimeframeScannerControllerTest {

    private MultiTimeframeScannerService scannerService;
    private HourlyMtfScannerScheduler scheduler;
    private MultiTimeframeScannerController controller;

    @BeforeEach
    void setUp() {
        scannerService = mock(MultiTimeframeScannerService.class);
        scheduler = mock(HourlyMtfScannerScheduler.class);
        controller = new MultiTimeframeScannerController(scannerService, scheduler);
    }

    @Test
    void testRunScanEndpoint() {
        MtfTrendStatus s1 =
                new MtfTrendStatus(
                        "BSE",
                        3400.0,
                        true,
                        3200.0,
                        3250.0,
                        true,
                        3300.0,
                        3280.0,
                        60.0,
                        true,
                        3350.0,
                        3320.0,
                        30.0,
                        true,
                        BigDecimal.valueOf(3400),
                        3320.0,
                        false);

        when(scannerService.scanAllNifty200()).thenReturn(List.of(s1));
        when(scannerService.getConfluenceUptrendStocks(List.of(s1))).thenReturn(List.of(s1));

        ResponseEntity<Map<String, Object>> response = controller.runScan();

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("status")).isEqualTo("SUCCESS");
        assertThat(response.getBody().get("confluenceCount")).isEqualTo(1);
    }

    @Test
    void testNotifyEndpoint() {
        MtfTrendStatus s1 =
                new MtfTrendStatus(
                        "VEDL",
                        270.0,
                        true,
                        250.0,
                        255.0,
                        true,
                        260.0,
                        258.0,
                        56.0,
                        true,
                        265.0,
                        266.0,
                        28.0,
                        true,
                        BigDecimal.valueOf(270),
                        266.0,
                        true);

        when(scheduler.runScanAndNotify()).thenReturn(List.of(s1));

        ResponseEntity<Map<String, Object>> response = controller.scanAndNotify();

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("telegramNotificationDispatched")).isEqualTo(true);
        assertThat(response.getBody().get("confluenceCount")).isEqualTo(1);
    }

    @Test
    void testStatusEndpoint() {
        when(scheduler.isEnabled()).thenReturn(true);

        ResponseEntity<Map<String, Object>> response = controller.getStatus();

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("universe")).isEqualTo("NIFTY 200");
        assertThat(response.getBody().get("hourlySchedulerEnabled")).isEqualTo(true);
    }
}
