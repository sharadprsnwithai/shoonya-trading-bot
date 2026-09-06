package com.tradingbot.controller;

import com.tradingbot.marketdata.ShoonyaOptionChainService;
import com.tradingbot.model.OptionChainResponse;
import java.math.BigDecimal;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for retrieving Option Chain data (ATM ± N strikes) for NIFTY 50 and custom
 * underlyings.
 */
@RestController
@RequestMapping("/api/v1/optionchain")
public class OptionChainController {

    private final ShoonyaOptionChainService optionChainService;

    public OptionChainController(ShoonyaOptionChainService optionChainService) {
        this.optionChainService = optionChainService;
    }

    /**
     * Get NIFTY 50 Option Chain (defaults to ATM ± 4 strikes). Example: GET
     * /api/v1/optionchain/nifty50?count=4&quotes=true
     */
    @GetMapping("/nifty50")
    public ResponseEntity<OptionChainResponse> getNifty50OptionChain(
            @RequestParam(defaultValue = "4") int count,
            @RequestParam(required = false) BigDecimal strike,
            @RequestParam(defaultValue = "true") boolean quotes) {
        OptionChainResponse response =
                optionChainService.getNifty50OptionChain(
                        strike, Math.max(1, Math.min(count, 15)), quotes);
        return ResponseEntity.ok(response);
    }

    /**
     * Get Put-Call Ratio (PCR) for NIFTY 50. Example: GET /api/v1/optionchain/pcr/nifty50?count=10
     */
    @GetMapping({"/pcr/nifty50", "/nifty50/pcr"})
    public ResponseEntity<com.tradingbot.model.PcrResponse> getNifty50Pcr(
            @RequestParam(defaultValue = "10") int count) {
        com.tradingbot.model.PcrResponse pcr =
                optionChainService.getNifty50Pcr(Math.max(1, Math.min(count, 25)));
        return ResponseEntity.ok(pcr);
    }
}
