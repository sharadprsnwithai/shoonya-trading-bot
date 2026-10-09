package com.tradingbot.strategy.car.controller;

import com.tradingbot.strategy.car.CarWeeklyGttService;
import com.tradingbot.strategy.car.model.CarHolding;
import com.tradingbot.strategy.car.model.CarPortfolioState;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/car")
public class CarWeeklyController {

    private final CarWeeklyGttService carService;

    public CarWeeklyController(CarWeeklyGttService carService) {
        this.carService = carService;
    }

    /**
     * Triggers the weekly routine. The run is idempotent per trading week: a second call (cron,
     * startup runner, Telegram command) is skipped unless {@code force=true} is passed explicitly.
     */
    @PostMapping("/run-weekly")
    public ResponseEntity<Map<String, Object>> runWeekly(
            @RequestParam(name = "force", defaultValue = "false") boolean force) {
        carService.runSundayWeeklyRoutine(force);
        return ResponseEntity.ok(
                Map.of(
                        "status",
                        "SUCCESS",
                        "force",
                        force,
                        "message",
                        "CAR weekly routine completed"));
    }

    @GetMapping("/performance")
    public ResponseEntity<CarPortfolioState> getPerformance() {
        return ResponseEntity.ok(carService.getPortfolioState());
    }

    @GetMapping("/holdings")
    public ResponseEntity<Map<String, CarHolding>> getHoldings() {
        return ResponseEntity.ok(carService.getPortfolioState().getHoldings());
    }
}
