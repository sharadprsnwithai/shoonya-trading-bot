# Decoupled Reactive Signal Stream & Multi-Broker Trade Execution Architecture

**Date:** 2026-03-30  
**Status:** DRAFT / UNDER REVIEW  
**Authors:** Trading Bot Core Engineering Team  

---

## 1. Executive Summary & Problem Statement

### 1.1 Current State
In the current implementation, trading strategies (such as the Lowest Volume Reversal service) tightly couple technical analysis, signal generation, and trade execution within monolithic services. Specifically, strategy scanning loops invoke broker order placement directly via `ShoonyaOrderService` or `ExecutionManager`.

### 1.2 Limitations of Current Architecture
1. **Single Broker Lock-in:** Order placement is tied to Shoonya broker APIs. Integrating alternative brokers (such as Zerodha Kite Connect, Angel One, or interactive test stubs) requires invasive changes to strategy code.
2. **Blocking I/O in Scanning Loops:** Network latency or temporary downtime in broker APIs delays the core scanning evaluation loop.
3. **No Multi-Account Support:** Cannot distribute identical strategy signals across multiple client accounts (e.g. running 1 Shoonya live account, 1 Zerodha live account, and 2 Zerodha paper accounts simultaneously with different lot multipliers).
4. **Tight Coupling:** Strategy business logic cannot be tested in isolation from execution management.

### 1.3 Target State
Decouple signal generation from trade execution using a reactive publish-subscribe architecture powered by Project Reactor (`Flux` / `Sinks.Many`). Strategy engines publish immutable `TradeSignal` events to an in-memory reactive event bus without waiting for order execution. Multiple pluggable broker consumers (e.g. Shoonya, Zerodha Kite, Paper Simulator) subscribe to the signal stream concurrently on isolated execution threads, applying their own account credentials, execution mode (`LIVE`/`PAPER`), and position sizing multipliers.

---

## 2. Core Architectural Design & SOLID Principles

```
                  ┌──────────────────────────────────────────────┐
                  │               Strategy Engines               │
                  │ (LowestVolumeReversal, VWAP-Supertrend, etc) │
                  └──────────────────────┬───────────────────────┘
                                         │
                                         ▼ (Dependency Inversion)
                  ┌──────────────────────────────────────────────┐
                  │            SignalPublisher (Interface)       │
                  ├──────────────────────────────────────────────┤
                  │            SignalEventBus (Implementation)   │
                  │             [ Sinks.Many<TradeSignal> ]      │
                  ├──────────────────────────────────────────────┤
                  │         SignalStreamProvider (Interface)     │
                  └──────────────────────┬───────────────────────┘
                                         │
                      Flux<TradeSignal> (Multicast Fan-out)
                                         │
         ┌───────────────────────────────┼───────────────────────────────┐
         │                               │                               │
         ▼ (Scheduler A)                 ▼ (Scheduler B)                 ▼ (Scheduler C)
┌────────────────────────────────┐ ┌───────────────────────────┐ ┌───────────────────────────┐
│     ShoonyaTradeConsumer       │ │   ZerodhaTradeConsumer    │ │   ZerodhaTradeConsumer    │
│    (id: "shoonya-primary")     │ │  (id: "zerodha-account-1")│ │  (id: "zerodha-account-2")│
│   [Mode: LIVE | Mult: 1.0x]    │ │ [Mode: PAPER | Mult: 2.0x]│ │ [Mode: LIVE | Mult: 1.0x] │
└───────────────┬────────────────┘ └─────────────┬─────────────┘ └─────────────┬─────────────┘
                │                                │                             │
                ▼                                ▼                             ▼
┌────────────────────────────────┐ ┌───────────────────────────┐ ┌───────────────────────────┐
│    ShoonyaBrokerGateway        │ │   ZerodhaBrokerGateway    │ │   ZerodhaBrokerGateway    │
│    (Shoonya REST API)          │ │   (Zerodha Kite SDK/HTTP) │ │   (Zerodha Kite SDK/HTTP) │
└────────────────────────────────┘ └───────────────────────────┘ └───────────────────────────┘
```

### 2.1 Clean Code & SOLID Alignment
* **Single Responsibility Principle (SRP):**
  - Strategy engines only analyze indicators and decide *what* signal to emit.
  - `SignalEventBus` only manages buffer storage and broadcasting of signal events.
  - `TradeExecutionConsumer` handles sizing, position state, and broker order mapping.
  - `BrokerOrderGateway` handles raw HTTP/REST communication with a specific broker API.
* **Open/Closed Principle (OCP):**
  - New broker integrations (e.g. AngelOne, Groww, Finvasia) can be added simply by implementing `BrokerOrderGateway` and `TradeExecutionConsumer` without altering existing strategies or other consumers.
* **Liskov Substitution Principle (LSP):**
  - All consumer implementations follow the contract defined in `TradeExecutionConsumer` and `AbstractTradeExecutionConsumer`.
* **Interface Segregation Principle (ISP):**
  - Publishers depend only on `SignalPublisher` (`publish(TradeSignal)`).
  - Consumers depend only on `SignalStreamProvider` (`getSignalStream()`).
* **Dependency Inversion Principle (DIP):**
  - High-level strategy modules depend on the `SignalPublisher` abstraction rather than concrete broker implementations.

---

## 3. Data Models & Signal Schema

### 3.1 Signal Action Lifecycle
The `SignalAction` enum models complete trade lifecycle states:
```java
public enum SignalAction {
    ENTRY_LONG,          // Buy entry (Futures Long or Option Buy)
    ENTRY_SHORT,         // Sell entry (Futures Short or Option Sell)
    EXIT_LONG,           // Full exit for long position
    EXIT_SHORT,          // Full exit for short position
    PARTIAL_EXIT_LONG,   // Partial profit take on long (e.g. 50% at 1:2 RR)
    PARTIAL_EXIT_SHORT,  // Partial profit take on short (e.g. 50% at 1:2 RR)
    UPDATE_STOP_LOSS,    // Trailing stop loss modification (e.g. cost floor / EMA trailing)
    HOLD                 // Informative / No-op
}
```

### 3.2 Enhanced `TradeSignal`
```java
package com.tradingbot.strategy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;

/**
 * Immutable trading signal emitted by strategy engines.
 */
public record TradeSignal(
        String signalId,
        String strategyId,
        String underlyingSymbol,
        String tradingSymbol,
        SignalAction action,
        BigDecimal price,
        BigDecimal stopLoss,
        BigDecimal targetPrice,
        int baseQuantity,
        String reason,
        Instant timestamp,
        Map<String, Object> metadata
) {
    public static TradeSignal of(
            String strategyId,
            String underlyingSymbol,
            String tradingSymbol,
            SignalAction action,
            BigDecimal price,
            BigDecimal stopLoss,
            BigDecimal targetPrice,
            int baseQuantity,
            String reason,
            Map<String, Object> metadata
    ) {
        return new TradeSignal(
                "SIG-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(),
                strategyId,
                underlyingSymbol,
                tradingSymbol,
                action,
                price,
                stopLoss,
                targetPrice,
                baseQuantity,
                reason,
                Instant.now(),
                metadata != null ? Collections.unmodifiableMap(metadata) : Collections.emptyMap()
        );
    }

    public boolean isActionable() {
        return action != null && action != SignalAction.HOLD;
    }
}
```

---

## 4. Reactive Signal Event Bus

### 4.1 Interfaces
```java
package com.tradingbot.bus;

import com.tradingbot.strategy.TradeSignal;
import reactor.core.publisher.Flux;

public interface SignalPublisher {
    boolean publish(TradeSignal signal);
}

public interface SignalStreamProvider {
    Flux<TradeSignal> getSignalStream();
}
```

### 4.2 `ReactiveSignalEventBus` Implementation
```java
package com.tradingbot.bus;

import com.tradingbot.strategy.TradeSignal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

@Component
public class ReactiveSignalEventBus implements SignalPublisher, SignalStreamProvider {
    private static final Logger log = LoggerFactory.getLogger(ReactiveSignalEventBus.class);
    private static final int BUFFER_CAPACITY = 1024;

    private final Sinks.Many<TradeSignal> sink;
    private final Flux<TradeSignal> signalFlux;

    public ReactiveSignalEventBus() {
        this.sink = Sinks.many().multicast().onBackpressureBuffer(BUFFER_CAPACITY, false);
        this.signalFlux = this.sink.asFlux().share();
    }

    @Override
    public boolean publish(TradeSignal signal) {
        if (signal == null || !signal.isActionable()) {
            return false;
        }

        Sinks.EmitResult result = sink.tryEmitNext(signal);
        if (result.isSuccess()) {
            log.info("[SIGNAL-BUS] Emitted signal: {} | Strategy: {} | {} {} @ {}",
                    signal.signalId(), signal.strategyId(), signal.action(), signal.tradingSymbol(), signal.price());
            return true;
        } else {
            log.error("[SIGNAL-BUS] Failed to emit signal {}: {}", signal.signalId(), result);
            return false;
        }
    }

    @Override
    public Flux<TradeSignal> getSignalStream() {
        return signalFlux;
    }
}
```

---

## 5. Multi-Broker Consumer Architecture

### 5.1 `BrokerOrderGateway` Contract
```java
package com.tradingbot.execution.gateway;

import com.tradingbot.model.order.OrderRequest;
import com.tradingbot.model.order.OrderResponse;

public interface BrokerOrderGateway {
    String getBrokerName();
    OrderResponse placeOrder(OrderRequest request);
    OrderResponse cancelOrder(String orderId);
    OrderResponse modifyOrder(String orderId, OrderRequest request);
}
```

### 5.2 `AbstractTradeExecutionConsumer`
Base class providing thread isolation, validation, quantity scaling, and error trapping:

```java
package com.tradingbot.execution.consumer;

import com.tradingbot.model.execution.ExecutionMode;
import com.tradingbot.strategy.TradeSignal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

public abstract class AbstractTradeExecutionConsumer implements TradeExecutionConsumer {
    protected final Logger log = LoggerFactory.getLogger(getClass());

    private final String consumerId;
    private final String brokerName;
    private final ExecutionMode executionMode;
    private final double quantityMultiplier;
    private final boolean enabled;
    private Disposable subscription;

    protected AbstractTradeExecutionConsumer(
            String consumerId,
            String brokerName,
            ExecutionMode executionMode,
            double quantityMultiplier,
            boolean enabled
    ) {
        this.consumerId = consumerId;
        this.brokerName = brokerName;
        this.executionMode = executionMode;
        this.quantityMultiplier = quantityMultiplier;
        this.enabled = enabled;
    }

    @Override
    public void start(Flux<TradeSignal> signalStream) {
        if (!enabled) {
            log.info("[CONSUMER:{}] Consumer is disabled. Skipping subscription.", consumerId);
            return;
        }

        log.info("[CONSUMER:{}] Subscribing to signal stream. Broker: {} | Mode: {} | Multiplier: {}x",
                consumerId, brokerName, executionMode, quantityMultiplier);

        this.subscription = signalStream
                .publishOn(Schedulers.boundedElastic()) // Isolated worker thread
                .filter(this::shouldProcessSignal)
                .doOnNext(this::processSignalSafe)
                .doOnError(err -> log.error("[CONSUMER:{}] Uncaught stream error: {}", consumerId, err.getMessage(), err))
                .retry()
                .subscribe();
    }

    @Override
    public void stop() {
        if (subscription != null && !subscription.isDisposed()) {
            subscription.dispose();
            log.info("[CONSUMER:{}] Disposed signal stream subscription.", consumerId);
        }
    }

    private void processSignalSafe(TradeSignal signal) {
        try {
            int scaledQuantity = calculateQuantity(signal.baseQuantity());
            if (scaledQuantity <= 0) {
                log.warn("[CONSUMER:{}] Computed quantity for signal {} is <= 0. Skipping.", consumerId, signal.signalId());
                return;
            }

            if (executionMode == ExecutionMode.PAPER) {
                handlePaperExecution(signal, scaledQuantity);
            } else {
                handleLiveExecution(signal, scaledQuantity);
            }
        } catch (Exception e) {
            log.error("[CONSUMER:{}] Error executing signal {}: {}", consumerId, signal.signalId(), e.getMessage(), e);
        }
    }

    protected int calculateQuantity(int baseQuantity) {
        return (int) Math.round(baseQuantity * quantityMultiplier);
    }

    protected boolean shouldProcessSignal(TradeSignal signal) {
        return signal != null && signal.isActionable();
    }

    protected abstract void handleLiveExecution(TradeSignal signal, int quantity);
    protected abstract void handlePaperExecution(TradeSignal signal, int quantity);

    @Override
    public String getConsumerId() { return consumerId; }
    @Override
    public String getBrokerName() { return brokerName; }
    @Override
    public ExecutionMode getExecutionMode() { return executionMode; }
    @Override
    public boolean isEnabled() { return enabled; }
}
```

### 5.3 Concrete Consumers
1. **`ShoonyaTradeConsumer`:** Interacts with `ShoonyaOrderService` / `ShoonyaBrokerGateway`.
2. **`ZerodhaTradeConsumer`:** Interacts with `ZerodhaBrokerGateway` (supporting Kite Connect integration).

---

## 6. Configuration Schema (`application.yml`)

Consumers and broker credentials are dynamically configurable via Spring configuration properties:

```yaml
trading-bot:
  execution:
    consumers:
      - id: "shoonya-live-primary"
        broker: "SHOONYA"
        enabled: true
        mode: "LIVE"
        quantity-multiplier: 1.0
        credentials:
          user-id: "${SHOONYA_USER_ID}"
          password: "${SHOONYA_PASSWORD}"
          totp-key: "${SHOONYA_TOTP_KEY}"
          vendor-code: "${SHOONYA_VENDOR_CODE}"
          api-key: "${SHOONYA_API_KEY}"
      - id: "zerodha-live-main"
        broker: "ZERODHA"
        enabled: false
        mode: "LIVE"
        quantity-multiplier: 2.0
        credentials:
          api-key: "${ZERODHA_API_KEY:}"
          api-secret: "${ZERODHA_API_SECRET:}"
          access-token: "${ZERODHA_ACCESS_TOKEN:}"
      - id: "zerodha-paper-test"
        broker: "ZERODHA"
        enabled: true
        mode: "PAPER"
        quantity-multiplier: 1.0
        credentials:
          api-key: "dummy"
          api-secret: "dummy"
          access-token: "dummy"
```

---

## 7. Migration Plan for Strategy Services

### 7.1 Decoupling `LowestVolumeReversalService`
1. Inject `SignalPublisher` into `LowestVolumeReversalService`.
2. Replace direct calls to local paper-trade mutations with `signalPublisher.publish(TradeSignal.of(...))`.
3. The strategy publishes:
   - `ENTRY_LONG` / `ENTRY_SHORT` when C4/C5 lowest volume breakout conditions are met.
   - `PARTIAL_EXIT_LONG` / `PARTIAL_EXIT_SHORT` when 1:2 RR Target 1 is reached.
   - `UPDATE_STOP_LOSS` when moving stop-loss to cost or adjusting to 10 EMA.
   - `EXIT_LONG` / `EXIT_SHORT` on stop-loss hit or 15:00 IST hard EOD exit.

---

## 8. REST Endpoints & Observability

### 8.1 API Controller Endpoints
* `GET /api/v1/execution/consumers`: Lists all active consumers, broker types, execution modes, multipliers, and health status.
* `POST /api/v1/execution/consumers/{consumerId}/toggle`: Enables or pauses a consumer at runtime.
* `POST /api/v1/signals/test`: Emits a test `TradeSignal` into the reactive bus to verify end-to-end multi-broker dispatching.

---

## 9. Testing & Quality Assurance Plan

1. **Reactive Stream Testing (`reactor-test`):**
   - Use `StepVerifier` to validate signal publishing, multicasting, backpressure buffering, and error recovery.
2. **Consumer Unit Tests:**
   - Verify lot sizing multiplier computations.
   - Verify that failure in Consumer A does not terminate or delay Consumer B.
3. **End-to-End Integration Tests:**
   - Simulate a full trade lifecycle (`ENTRY` -> `PARTIAL_EXIT` -> `UPDATE_SL` -> `EXIT`) emitted through `SignalPublisher` and verified across multiple mock broker gateways.
4. **Code Quality Standards:**
   - Spotless formatting compliance.
   - SpotBugs static analysis clean.
   - JaCoCo test coverage on all new components.
