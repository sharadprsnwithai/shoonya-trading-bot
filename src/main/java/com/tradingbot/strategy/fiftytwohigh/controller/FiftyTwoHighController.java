package com.tradingbot.strategy.fiftytwohigh.controller;

import com.tradingbot.strategy.fiftytwohigh.model.FiftyTwoHighBreakout;
import com.tradingbot.strategy.fiftytwohigh.model.FiftyTwoHighReport;
import com.tradingbot.strategy.fiftytwohigh.service.FiftyTwoHighService;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for the NSE F&amp;O 52-week-high breakout scanner.
 *
 * <p>{@code GET /scan} computes and returns the report without touching Telegram; {@code POST /run}
 * computes, dispatches the Telegram summary, and returns the same report — mirroring the
 * monthly-range controller's split between read and notify.
 */
@RestController
@RequestMapping("/api/v1/fifty-two-week-high")
public class FiftyTwoHighController {

    private final FiftyTwoHighService service;

    public FiftyTwoHighController(FiftyTwoHighService service) {
        this.service = service;
    }

    /** Computes the breakout report for the whole universe (no Telegram dispatch). */
    @GetMapping("/scan")
    public ResponseEntity<FiftyTwoHighReport> scan() {
        return ResponseEntity.ok(service.scan());
    }

    /** Computes the breakout report and dispatches the Telegram summary. */
    @PostMapping("/run")
    public ResponseEntity<FiftyTwoHighReport> run() {
        return ResponseEntity.ok(service.scanAndAlert());
    }

    /** Evaluates a single symbol; 404 when it did not cross its 52-week high. */
    @GetMapping("/scan/{symbol}")
    public ResponseEntity<FiftyTwoHighBreakout> scanSingle(@PathVariable String symbol) {
        FiftyTwoHighBreakout b = service.scanSingle(symbol);
        if (b == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(b);
    }

    /** Lightweight health probe so operators can confirm the scanner bean is wired. */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        return ResponseEntity.ok(Map.of("status", "ok", "scanner", "fifty-two-week-high"));
    }
}
