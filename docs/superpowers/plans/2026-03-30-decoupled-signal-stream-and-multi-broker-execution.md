# Decoupled Reactive Signal Stream & Multi-Broker Trade Execution Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Decouple strategy signal generation from trade execution using a Project Reactor reactive hot multicast event bus (`Flux` / `Sinks.Many`), delegating trade execution to pluggable multi-broker consumers (Shoonya, Zerodha, Paper) with isolated threads, stale signal rejection, and account-specific position sizing multipliers, while removing legacy execution coupling from strategy engines.

**Architecture:** Strategy engines (e.g. `LowestVolumeReversalService`) emit immutable `TradeSignal` events to a `SignalPublisher`. The `ReactiveSignalEventBus` multicasts these signals over a hot `Flux<TradeSignal>` without replay history. Multiple `TradeExecutionConsumer` instances (e.g. `ShoonyaTradeConsumer`, `ZerodhaTradeConsumer`) subscribe on isolated `Schedulers.boundedElastic()` threads, validate signal age against `maxSignalAgeSeconds`, scale quantities using consumer-specific multipliers, and delegate order routing to `BrokerOrderGateway` implementations.

**Tech Stack:** Java 21, Spring Boot 3.3.5, Project Reactor (`reactor-core`, `reactor-test`), JUnit 5, Mockito, AssertJ.

**Spec:** `docs/superpowers/specs/2026-03-30-decoupled-signal-stream-and-multi-broker-execution-design.md`

---

## Global Constraints

- Language & Framework: Java 21 toolchain with Spring Boot 3.3.5.
- Code Standards: Formatted with Spotless Google Java Format (`./gradlew spotlessApply`), zero SpotBugs errors (`./gradlew spotbugsMain spotbugsTest`), and all unit/integration tests passing (`./gradlew test`).
- Concurrency & Thread Isolation: Consumers must never block each other or the signal publisher thread. Execution happens on dedicated worker threads.
- Hot Multicast Only: No historical replay of signals to late subscribers.
- Stale Signal Defense: Any signal with `age > maxSignalAgeSeconds` (default: 30s) must be dropped before reaching broker gateways.
- SOLID & Clean Architecture: Maintain strict separation between Strategy (Analyzer), Bus (PubSub), Consumer (Coordinator/Sizer), and Gateway (Broker I/O).

## Review Focus

1. **Late Subscriber Stale Signal Ingestion:** A consumer subscribing after signals were emitted must not receive past signals, and any delayed signal must be dropped by `isSignalFreshAndActionable`.
2. **Broker Failure Isolation:** An uncaught runtime exception, timeout, or failure in Shoonya Consumer must not prevent Zerodha Consumer from executing the same signal.
3. **Zero / Negative Scaled Quantity Handling:** A consumer with a multiplier resulting in `0` quantity must drop the order cleanly without emitting invalid broker requests.
4. **Non-Actionable Signal Filtering:** `SignalAction.HOLD` or non-actionable signals must never trigger order execution across any consumer.
5. **Thread Non-Blocking:** The strategy scanning thread calling `publish(signal)` must complete instantly without waiting for consumer HTTP calls or broker response roundtrips.

---

## File Structure & Responsibilities

```
src/main/java/com/tradingbot/
├── bus/
│   ├── SignalPublisher.java                   (Interface: publish signals)
│   ├── SignalStreamProvider.java               (Interface: access reactive signal Flux)
│   └── ReactiveSignalEventBus.java            (Implementation: Project Reactor hot Sinks.Many bus)
├── strategy/
│   ├── SignalAction.java                      (Enum: ENTRY_LONG, EXIT_SHORT, UPDATE_STOP_LOSS, etc.)
│   └── TradeSignal.java                       (Record: immutable signal payload with metadata)
├── execution/
│   ├── config/
│   │   └── ExecutionProperties.java           (Config properties: consumers list, broker creds, multipliers)
│   ├── gateway/
│   │   ├── BrokerOrderGateway.java            (Interface: broker order placement abstraction)
│   │   ├── ShoonyaBrokerGateway.java          (Implementation: Shoonya API adapter)
│   │   └── ZerodhaBrokerGateway.java          (Implementation: Zerodha Kite adapter/stub)
│   ├── consumer/
│   │   ├── TradeExecutionConsumer.java        (Interface: consumer contract & lifecycle)
│   │   ├── AbstractTradeExecutionConsumer.java(Base class: isolation, freshness check, scaling)
│   │   ├── ShoonyaTradeConsumer.java          (Shoonya consumer implementation)
│   │   ├── ZerodhaTradeConsumer.java          (Zerodha consumer implementation)
│   │   └── TradeConsumerManager.java          (Service: registers and manages consumer lifecycles)
│   └── controller/
│       └── ExecutionConsumerController.java   (REST API: query status, toggle consumers, test signals)
└── service/
    └── LowestVolumeReversalService.java       (Strategy engine: publishes signals, legacy execution removed)
```

---

## Task Decomposition

### Task 1: Add Project Reactor Dependencies & Modernize Signal Domain Model

**Files:**
- Modify: `build.gradle.kts:25-45`
- Modify: `src/main/java/com/tradingbot/strategy/SignalAction.java`
- Modify: `src/main/java/com/tradingbot/strategy/TradeSignal.java`
- Test: `src/test/java/com/tradingbot/strategy/TradeSignalTest.java`

**Interfaces:**
- Consumes: Java standard library, Project Reactor dependencies.
- Produces: `SignalAction` enum, `TradeSignal` immutable record with `signalId`, `underlyingSymbol`, `tradingSymbol`, `baseQuantity`, `timestamp`, and `metadata`.

- [ ] **Step 1: Add Project Reactor dependencies in `build.gradle.kts`**

```kotlin
    // Reactive Streams & Project Reactor
    implementation("io.projectreactor:reactor-core:3.6.11")
    testImplementation("io.projectreactor:reactor-test:3.6.11")
```

- [ ] **Step 2: Write failing unit test `TradeSignalTest.java`**

```java
package com.tradingbot.strategy;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TradeSignalTest {

    @Test
    void testCreateActionableSignal() {
        TradeSignal signal =
                TradeSignal.of(
                        "LVR_FUTURES",
                        "RELIANCE",
                        "RELIANCE26MARFUT",
                        SignalAction.ENTRY_LONG,
                        BigDecimal.valueOf(2500.0),
                        BigDecimal.valueOf(2480.0),
                        BigDecimal.valueOf(2540.0),
                        250,
                        "C4 green dry-up breakout",
                        Map.of("instrumentType", "FUTURES"));

        assertNotNull(signal.signalId());
        assertTrue(signal.signalId().startsWith("SIG-"));
        assertEquals("LVR_FUTURES", signal.strategyId());
        assertEquals("RELIANCE", signal.underlyingSymbol());
        assertEquals("RELIANCE26MARFUT", signal.tradingSymbol());
        assertEquals(SignalAction.ENTRY_LONG, signal.action());
        assertEquals(BigDecimal.valueOf(2500.0), signal.price());
        assertEquals(250, signal.baseQuantity());
        assertTrue(signal.isActionable());
        assertNotNull(signal.timestamp());
    }

    @Test
    void testHoldSignalIsNotActionable() {
        TradeSignal holdSignal = TradeSignal.hold("LVR_FUTURES", "TCS", "No setup");
        assertFalse(holdSignal.isActionable());
        assertEquals(SignalAction.HOLD, holdSignal.action());
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew test --tests com.tradingbot.strategy.TradeSignalTest`
Expected: FAIL (compilation errors on missing methods/fields).

- [ ] **Step 4: Update `SignalAction.java` and `TradeSignal.java`**

Update `SignalAction.java`:
```java
package com.tradingbot.strategy;

public enum SignalAction {
    BUY,
    SELL,
    ENTRY_LONG,
    ENTRY_SHORT,
    EXIT_LONG,
    EXIT_SHORT,
    PARTIAL_EXIT_LONG,
    PARTIAL_EXIT_SHORT,
    UPDATE_STOP_LOSS,
    HOLD
}
```

Update `TradeSignal.java`:
```java
package com.tradingbot.strategy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;

/** Immutable trading signal emitted by strategy engines. */
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
        Map<String, Object> metadata) {

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
            Map<String, Object> metadata) {
        String id = "SIG-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        return new TradeSignal(
                id,
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
                metadata != null ? Collections.unmodifiableMap(metadata) : Collections.emptyMap());
    }

    public static TradeSignal of(
            String strategyId,
            String symbol,
            SignalAction action,
            BigDecimal price,
            BigDecimal stopLoss,
            BigDecimal targetPrice,
            int quantity,
            String reason) {
        return of(
                strategyId,
                symbol,
                symbol,
                action,
                price,
                stopLoss,
                targetPrice,
                quantity,
                reason,
                Collections.emptyMap());
    }

    public static TradeSignal of(
            String strategyId,
            String symbol,
            SignalAction action,
            BigDecimal price,
            BigDecimal stopLoss,
            BigDecimal targetPrice,
            int quantity,
            String reason,
            Map<String, Object> metadata) {
        return of(
                strategyId,
                symbol,
                symbol,
                action,
                price,
                stopLoss,
                targetPrice,
                quantity,
                reason,
                metadata);
    }

    public static TradeSignal hold(String strategyId, String symbol, String reason) {
        return new TradeSignal(
                "SIG-HOLD",
                strategyId,
                symbol,
                symbol,
                SignalAction.HOLD,
                BigDecimal.ZERO,
                null,
                null,
                0,
                reason,
                Instant.now(),
                Collections.emptyMap());
    }

    public boolean isActionable() {
        return action != null && action != SignalAction.HOLD;
    }
}
```

- [ ] **Step 5: Run tests and verify they pass**

Run: `./gradlew test --tests com.tradingbot.strategy.TradeSignalTest`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add build.gradle.kts src/main/java/com/tradingbot/strategy/ src/test/java/com/tradingbot/strategy/
git commit -m "feat: add reactor dependencies and enhance TradeSignal domain model"
```

---

### Task 2: Implement Reactive Signal Event Bus (`ReactiveSignalEventBus`)

**Files:**
- Create: `src/main/java/com/tradingbot/bus/SignalPublisher.java`
- Create: `src/main/java/com/tradingbot/bus/SignalStreamProvider.java`
- Create: `src/main/java/com/tradingbot/bus/ReactiveSignalEventBus.java`
- Test: `src/test/java/com/tradingbot/bus/ReactiveSignalEventBusTest.java`

**Interfaces:**
- Consumes: `TradeSignal`, Project Reactor `Flux`, `Sinks.Many`.
- Produces: `SignalPublisher.publish(TradeSignal)`, `SignalStreamProvider.getSignalStream() -> Flux<TradeSignal>`.

- [ ] **Step 1: Write failing reactive tests in `ReactiveSignalEventBusTest.java`**

```java
package com.tradingbot.bus;

import static org.junit.jupiter.api.Assertions.*;

import com.tradingbot.strategy.SignalAction;
import com.tradingbot.strategy.TradeSignal;
import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

class ReactiveSignalEventBusTest {

    private ReactiveSignalEventBus bus;

    @BeforeEach
    void setUp() {
        bus = new ReactiveSignalEventBus();
    }

    @Test
    void testMulticastSignalBroadcastToMultipleSubscribers() {
        Flux<TradeSignal> stream = bus.getSignalStream();
        AtomicInteger sub1Count = new AtomicInteger(0);
        AtomicInteger sub2Count = new AtomicInteger(0);

        stream.subscribe(sig -> sub1Count.incrementAndGet());
        stream.subscribe(sig -> sub2Count.incrementAndGet());

        TradeSignal signal =
                TradeSignal.of(
                        "LVR",
                        "SBIN",
                        "SBIN26MARFUT",
                        SignalAction.ENTRY_LONG,
                        BigDecimal.valueOf(800),
                        BigDecimal.valueOf(790),
                        BigDecimal.valueOf(820),
                        750,
                        "Breakout",
                        null);

        boolean published = bus.publish(signal);
        assertTrue(published);
        assertEquals(1, sub1Count.get());
        assertEquals(1, sub2Count.get());
    }

    @Test
    void testIgnoreNonActionableHoldSignal() {
        TradeSignal hold = TradeSignal.hold("LVR", "INFY", "No trigger");
        boolean published = bus.publish(hold);
        assertFalse(published);
    }

    @Test
    void testLateSubscriberDoesNotReceivePastSignals() {
        TradeSignal signal1 =
                TradeSignal.of(
                        "LVR",
                        "TCS",
                        "TCS26MARFUT",
                        SignalAction.ENTRY_LONG,
                        BigDecimal.valueOf(3500),
                        null,
                        null,
                        175,
                        "Early signal",
                        null);
        bus.publish(signal1);

        AtomicInteger lateSubCount = new AtomicInteger(0);
        bus.getSignalStream().subscribe(sig -> lateSubCount.incrementAndGet());

        TradeSignal signal2 =
                TradeSignal.of(
                        "LVR",
                        "WIPRO",
                        "WIPRO26MARFUT",
                        SignalAction.ENTRY_SHORT,
                        BigDecimal.valueOf(500),
                        null,
                        null,
                        1500,
                        "Late signal",
                        null);
        bus.publish(signal2);

        assertEquals(1, lateSubCount.get());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.tradingbot.bus.ReactiveSignalEventBusTest`
Expected: FAIL (classes not found).

- [ ] **Step 3: Implement `SignalPublisher`, `SignalStreamProvider`, and `ReactiveSignalEventBus`**

Create `SignalPublisher.java`:
```java
package com.tradingbot.bus;

import com.tradingbot.strategy.TradeSignal;

public interface SignalPublisher {
    boolean publish(TradeSignal signal);
}
```

Create `SignalStreamProvider.java`:
```java
package com.tradingbot.bus;

import com.tradingbot.strategy.TradeSignal;
import reactor.core.publisher.Flux;

public interface SignalStreamProvider {
    Flux<TradeSignal> getSignalStream();
}
```

Create `ReactiveSignalEventBus.java`:
```java
package com.tradingbot.bus;

import com.tradingbot.strategy.TradeSignal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/**
 * Hot multicast reactive event bus for trading signals.
 * Uses direct multicast without replay cache, ensuring late subscribers only receive future signals.
 */
@Component
public class ReactiveSignalEventBus implements SignalPublisher, SignalStreamProvider {

    private static final Logger log = LoggerFactory.getLogger(ReactiveSignalEventBus.class);
    public static final int BUFFER_CAPACITY = 1024;

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
            log.info(
                    "[SIGNAL-BUS] Emitted signal: {} | Strategy: {} | {} {} @ {}",
                    signal.signalId(),
                    signal.strategyId(),
                    signal.action(),
                    signal.tradingSymbol(),
                    signal.price());
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

- [ ] **Step 4: Run tests and verify they pass**

Run: `./gradlew test --tests com.tradingbot.bus.ReactiveSignalEventBusTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/tradingbot/bus/ src/test/java/com/tradingbot/bus/
git commit -m "feat: implement ReactiveSignalEventBus with hot multicast stream"
```

---

### Task 3: Implement Abstract Consumer & Stale Signal Prevention Filter

**Files:**
- Create: `src/main/java/com/tradingbot/execution/consumer/TradeExecutionConsumer.java`
- Create: `src/main/java/com/tradingbot/execution/consumer/AbstractTradeExecutionConsumer.java`
- Test: `src/test/java/com/tradingbot/execution/consumer/AbstractTradeExecutionConsumerTest.java`

**Interfaces:**
- Consumes: `TradeSignal`, `SignalStreamProvider`, `ExecutionMode`.
- Produces: `TradeExecutionConsumer` interface, lifecycle `start(Flux<TradeSignal>)` & `stop()`, quantity scaling, and staleness filtering.

- [ ] **Step 1: Write unit tests in `AbstractTradeExecutionConsumerTest.java`**

```java
package com.tradingbot.execution.consumer;

import static org.junit.jupiter.api.Assertions.*;

import com.tradingbot.model.execution.ExecutionMode;
import com.tradingbot.strategy.SignalAction;
import com.tradingbot.strategy.TradeSignal;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Sinks;

class AbstractTradeExecutionConsumerTest {

    static class TestConsumer extends AbstractTradeExecutionConsumer {
        final List<TradeSignal> liveSignals = new ArrayList<>();
        final List<Integer> liveQuantities = new ArrayList<>();
        final List<TradeSignal> paperSignals = new ArrayList<>();
        final List<Integer> paperQuantities = new ArrayList<>();

        TestConsumer(
                String id,
                String broker,
                ExecutionMode mode,
                double multiplier,
                boolean enabled,
                long maxAgeSeconds) {
            super(id, broker, mode, multiplier, enabled, maxAgeSeconds);
        }

        @Override
        protected void handleLiveExecution(TradeSignal signal, int quantity) {
            liveSignals.add(signal);
            liveQuantities.add(quantity);
        }

        @Override
        protected void handlePaperExecution(TradeSignal signal, int quantity) {
            paperSignals.add(signal);
            paperQuantities.add(quantity);
        }
    }

    @Test
    void testQuantityMultiplierAppliedCorrectly() throws InterruptedException {
        TestConsumer consumer =
                new TestConsumer("test-c1", "TEST_BROKER", ExecutionMode.LIVE, 2.5, true, 30);
        Sinks.Many<TradeSignal> sink = Sinks.many().multicast().onBackpressureBuffer();
        consumer.start(sink.asFlux());

        TradeSignal signal =
                TradeSignal.of(
                        "LVR",
                        "NIFTY",
                        "NIFTY26MARFUT",
                        SignalAction.ENTRY_LONG,
                        BigDecimal.valueOf(22000),
                        null,
                        null,
                        100,
                        "Entry",
                        null);

        sink.tryEmitNext(signal);
        Thread.sleep(100);

        assertEquals(1, consumer.liveSignals.size());
        assertEquals(250, consumer.liveQuantities.get(0)); // 100 * 2.5 = 250
        consumer.stop();
    }

    @Test
    void testStaleSignalIsRejected() throws InterruptedException {
        TestConsumer consumer =
                new TestConsumer("test-c2", "TEST_BROKER", ExecutionMode.LIVE, 1.0, true, 5); // 5 sec max age
        Sinks.Many<TradeSignal> sink = Sinks.many().multicast().onBackpressureBuffer();
        consumer.start(sink.asFlux());

        // Signal emitted 10 seconds ago
        TradeSignal staleSignal =
                new TradeSignal(
                        "SIG-OLD",
                        "LVR",
                        "INFY",
                        "INFY26MARFUT",
                        SignalAction.ENTRY_LONG,
                        BigDecimal.valueOf(1500),
                        null,
                        null,
                        400,
                        "Old signal",
                        Instant.now().minus(Duration.ofSeconds(10)),
                        null);

        sink.tryEmitNext(staleSignal);
        Thread.sleep(100);

        assertEquals(0, consumer.liveSignals.size());
        consumer.stop();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.tradingbot.execution.consumer.AbstractTradeExecutionConsumerTest`
Expected: FAIL.

- [ ] **Step 3: Implement `TradeExecutionConsumer` and `AbstractTradeExecutionConsumer`**

Create `TradeExecutionConsumer.java`:
```java
package com.tradingbot.execution.consumer;

import com.tradingbot.model.execution.ExecutionMode;
import com.tradingbot.strategy.TradeSignal;
import reactor.core.publisher.Flux;

public interface TradeExecutionConsumer {
    String getConsumerId();
    String getBrokerName();
    ExecutionMode getExecutionMode();
    boolean isEnabled();
    double getQuantityMultiplier();
    void start(Flux<TradeSignal> signalStream);
    void stop();
}
```

Create `AbstractTradeExecutionConsumer.java`:
```java
package com.tradingbot.execution.consumer;

import com.tradingbot.model.execution.ExecutionMode;
import com.tradingbot.strategy.TradeSignal;
import java.time.Duration;
import java.time.Instant;
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
    private final long maxSignalAgeSeconds;
    private Disposable subscription;

    protected AbstractTradeExecutionConsumer(
            String consumerId,
            String brokerName,
            ExecutionMode executionMode,
            double quantityMultiplier,
            boolean enabled,
            long maxSignalAgeSeconds) {
        this.consumerId = consumerId;
        this.brokerName = brokerName;
        this.executionMode = executionMode;
        this.quantityMultiplier = quantityMultiplier > 0 ? quantityMultiplier : 1.0;
        this.enabled = enabled;
        this.maxSignalAgeSeconds = maxSignalAgeSeconds > 0 ? maxSignalAgeSeconds : 30L;
    }

    @Override
    public void start(Flux<TradeSignal> signalStream) {
        if (!enabled) {
            log.info("[CONSUMER:{}] Consumer is disabled. Skipping subscription.", consumerId);
            return;
        }

        log.info(
                "[CONSUMER:{}] Subscribing to signal stream. Broker: {} | Mode: {} | Multiplier: {}x | MaxAge: {}s",
                consumerId,
                brokerName,
                executionMode,
                quantityMultiplier,
                maxSignalAgeSeconds);

        this.subscription =
                signalStream
                        .publishOn(Schedulers.boundedElastic())
                        .filter(this::isSignalFreshAndActionable)
                        .doOnNext(this::processSignalSafe)
                        .doOnError(
                                err ->
                                        log.error(
                                                "[CONSUMER:{}] Uncaught stream error: {}",
                                                consumerId,
                                                err.getMessage(),
                                                err))
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

    protected boolean isSignalFreshAndActionable(TradeSignal signal) {
        if (signal == null || !signal.isActionable()) {
            return false;
        }
        if (signal.timestamp() != null) {
            long ageSeconds = Duration.between(signal.timestamp(), Instant.now()).getSeconds();
            if (ageSeconds > maxSignalAgeSeconds) {
                log.warn(
                        "[CONSUMER:{}] Dropping STALE signal {} (Age: {}s > Max: {}s) for {}",
                        consumerId,
                        signal.signalId(),
                        ageSeconds,
                        maxSignalAgeSeconds,
                        signal.tradingSymbol());
                return false;
            }
        }
        return true;
    }

    private void processSignalSafe(TradeSignal signal) {
        try {
            int targetQty = calculateQuantity(signal.baseQuantity());
            if (targetQty <= 0) {
                log.warn(
                        "[CONSUMER:{}] Computed quantity for signal {} is <= 0. Skipping order.",
                        consumerId,
                        signal.signalId());
                return;
            }

            if (executionMode == ExecutionMode.PAPER) {
                handlePaperExecution(signal, targetQty);
            } else {
                handleLiveExecution(signal, targetQty);
            }
        } catch (Exception e) {
            log.error(
                    "[CONSUMER:{}] Execution failed for signal {}: {}",
                    consumerId,
                    signal.signalId(),
                    e.getMessage(),
                    e);
        }
    }

    protected int calculateQuantity(int baseQuantity) {
        return (int) Math.round(baseQuantity * quantityMultiplier);
    }

    protected abstract void handleLiveExecution(TradeSignal signal, int quantity);

    protected abstract void handlePaperExecution(TradeSignal signal, int quantity);

    @Override
    public String getConsumerId() {
        return consumerId;
    }

    @Override
    public String getBrokerName() {
        return brokerName;
    }

    @Override
    public ExecutionMode getExecutionMode() {
        return executionMode;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public double getQuantityMultiplier() {
        return quantityMultiplier;
    }
}
```

- [ ] **Step 4: Run tests and verify they pass**

Run: `./gradlew test --tests com.tradingbot.execution.consumer.AbstractTradeExecutionConsumerTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/tradingbot/execution/consumer/ src/test/java/com/tradingbot/execution/consumer/
git commit -m "feat: implement AbstractTradeExecutionConsumer with stale rejection and thread isolation"
```

---

### Task 4: Implement Broker Gateways & Concrete Consumers (Shoonya & Zerodha)

**Files:**
- Create: `src/main/java/com/tradingbot/execution/gateway/BrokerOrderGateway.java`
- Create: `src/main/java/com/tradingbot/execution/gateway/ShoonyaBrokerGateway.java`
- Create: `src/main/java/com/tradingbot/execution/gateway/ZerodhaBrokerGateway.java`
- Create: `src/main/java/com/tradingbot/execution/consumer/ShoonyaTradeConsumer.java`
- Create: `src/main/java/com/tradingbot/execution/consumer/ZerodhaTradeConsumer.java`
- Test: `src/test/java/com/tradingbot/execution/consumer/ShoonyaTradeConsumerTest.java`

**Interfaces:**
- Consumes: `ShoonyaOrderService`, `BrokerOrderGateway`, `TradeSignal`.
- Produces: `ShoonyaTradeConsumer`, `ZerodhaTradeConsumer`.

- [ ] **Step 1: Write unit test in `ShoonyaTradeConsumerTest.java`**

```java
package com.tradingbot.execution.consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.tradingbot.execution.gateway.BrokerOrderGateway;
import com.tradingbot.model.execution.ExecutionMode;
import com.tradingbot.model.order.OrderRequest;
import com.tradingbot.model.order.OrderResponse;
import com.tradingbot.model.order.TransactionType;
import com.tradingbot.strategy.SignalAction;
import com.tradingbot.strategy.TradeSignal;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Sinks;

class ShoonyaTradeConsumerTest {

    @Test
    void testShoonyaConsumerExecutesLiveOrder() throws InterruptedException {
        BrokerOrderGateway mockGateway = mock(BrokerOrderGateway.class);
        when(mockGateway.placeOrder(any(OrderRequest.class)))
                .thenReturn(new OrderResponse("ORD_123", true, "Order placed", "COMPLETE"));

        ShoonyaTradeConsumer consumer =
                new ShoonyaTradeConsumer(
                        "shoonya-live",
                        ExecutionMode.LIVE,
                        1.0,
                        true,
                        30,
                        mockGateway);

        Sinks.Many<TradeSignal> sink = Sinks.many().multicast().onBackpressureBuffer();
        consumer.start(sink.asFlux());

        TradeSignal signal =
                TradeSignal.of(
                        "LVR_FUTURES",
                        "RELIANCE",
                        "RELIANCE26MARFUT",
                        SignalAction.ENTRY_LONG,
                        BigDecimal.valueOf(2500),
                        BigDecimal.valueOf(2480),
                        BigDecimal.valueOf(2540),
                        250,
                        "Breakout",
                        null);

        sink.tryEmitNext(signal);
        Thread.sleep(100);

        verify(mockGateway, times(1)).placeOrder(argThat(req ->
                req.tradingSymbol().equals("RELIANCE26MARFUT") &&
                req.transactionType() == TransactionType.BUY &&
                req.quantity() == 250
        ));
        consumer.stop();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.tradingbot.execution.consumer.ShoonyaTradeConsumerTest`
Expected: FAIL.

- [ ] **Step 3: Implement Gateway interface and Shoonya / Zerodha implementations**

Create `BrokerOrderGateway.java`:
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

Create `ShoonyaBrokerGateway.java`:
```java
package com.tradingbot.execution.gateway;

import com.tradingbot.model.order.OrderRequest;
import com.tradingbot.model.order.OrderResponse;
import com.tradingbot.order.ShoonyaOrderService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class ShoonyaBrokerGateway implements BrokerOrderGateway {

    private final ShoonyaOrderService orderService;

    @Autowired
    public ShoonyaBrokerGateway(ShoonyaOrderService orderService) {
        this.orderService = orderService;
    }

    @Override
    public String getBrokerName() {
        return "SHOONYA";
    }

    @Override
    public OrderResponse placeOrder(OrderRequest request) {
        return orderService.placeOrder(request);
    }

    @Override
    public OrderResponse cancelOrder(String orderId) {
        return orderService.cancelOrder(orderId);
    }

    @Override
    public OrderResponse modifyOrder(String orderId, OrderRequest request) {
        return orderService.modifyOrder(orderId, request);
    }
}
```

Create `ZerodhaBrokerGateway.java`:
```java
package com.tradingbot.execution.gateway;

import com.tradingbot.model.order.OrderRequest;
import com.tradingbot.model.order.OrderResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class ZerodhaBrokerGateway implements BrokerOrderGateway {

    private static final Logger log = LoggerFactory.getLogger(ZerodhaBrokerGateway.class);

    @Override
    public String getBrokerName() {
        return "ZERODHA";
    }

    @Override
    public OrderResponse placeOrder(OrderRequest request) {
        log.info("[ZERODHA-GATEWAY] Routing order to Zerodha Kite API: {} {} x {}",
                request.transactionType(), request.tradingSymbol(), request.quantity());
        // Adapter for KiteConnect placeOrder() API
        return new OrderResponse("KITE_" + System.currentTimeMillis(), true, "Zerodha order placed", "TRIGGER PENDING");
    }

    @Override
    public OrderResponse cancelOrder(String orderId) {
        log.info("[ZERODHA-GATEWAY] Cancelling Zerodha order: {}", orderId);
        return new OrderResponse(orderId, true, "Zerodha order cancelled", "CANCELLED");
    }

    @Override
    public OrderResponse modifyOrder(String orderId, OrderRequest request) {
        log.info("[ZERODHA-GATEWAY] Modifying Zerodha order: {}", orderId);
        return new OrderResponse(orderId, true, "Zerodha order modified", "MODIFIED");
    }
}
```

Create `ShoonyaTradeConsumer.java`:
```java
package com.tradingbot.execution.consumer;

import com.tradingbot.execution.gateway.BrokerOrderGateway;
import com.tradingbot.model.execution.ExecutionMode;
import com.tradingbot.model.order.OrderRequest;
import com.tradingbot.model.order.OrderResponse;
import com.tradingbot.model.order.OrderType;
import com.tradingbot.model.order.ProductType;
import com.tradingbot.model.order.TransactionType;
import com.tradingbot.strategy.SignalAction;
import com.tradingbot.strategy.TradeSignal;
import java.math.BigDecimal;

public class ShoonyaTradeConsumer extends AbstractTradeExecutionConsumer {

    private final BrokerOrderGateway orderGateway;

    public ShoonyaTradeConsumer(
            String consumerId,
            ExecutionMode executionMode,
            double quantityMultiplier,
            boolean enabled,
            long maxSignalAgeSeconds,
            BrokerOrderGateway orderGateway) {
        super(consumerId, "SHOONYA", executionMode, quantityMultiplier, enabled, maxSignalAgeSeconds);
        this.orderGateway = orderGateway;
    }

    @Override
    protected void handleLiveExecution(TradeSignal signal, int quantity) {
        TransactionType txnType =
                (signal.action() == SignalAction.ENTRY_LONG || signal.action() == SignalAction.BUY || signal.action() == SignalAction.EXIT_SHORT)
                        ? TransactionType.BUY
                        : TransactionType.SELL;

        OrderRequest request =
                new OrderRequest(
                        signal.tradingSymbol(),
                        "NFO",
                        txnType,
                        OrderType.MKT,
                        ProductType.MIS,
                        quantity,
                        BigDecimal.ZERO,
                        null,
                        signal.signalId());

        OrderResponse resp = orderGateway.placeOrder(request);
        log.info("[CONSUMER:{}] Live order placed: {} | Result: {}", getConsumerId(), signal.tradingSymbol(), resp);
    }

    @Override
    protected void handlePaperExecution(TradeSignal signal, int quantity) {
        log.info(
                "[CONSUMER:{}:PAPER] Simulated trade executed: {} {} x {} @ {}",
                getConsumerId(),
                signal.action(),
                signal.tradingSymbol(),
                quantity,
                signal.price());
    }
}
```

Create `ZerodhaTradeConsumer.java`:
```java
package com.tradingbot.execution.consumer;

import com.tradingbot.execution.gateway.BrokerOrderGateway;
import com.tradingbot.model.execution.ExecutionMode;
import com.tradingbot.model.order.OrderRequest;
import com.tradingbot.model.order.OrderResponse;
import com.tradingbot.model.order.OrderType;
import com.tradingbot.model.order.ProductType;
import com.tradingbot.model.order.TransactionType;
import com.tradingbot.strategy.SignalAction;
import com.tradingbot.strategy.TradeSignal;
import java.math.BigDecimal;

public class ZerodhaTradeConsumer extends AbstractTradeExecutionConsumer {

    private final BrokerOrderGateway orderGateway;

    public ZerodhaTradeConsumer(
            String consumerId,
            ExecutionMode executionMode,
            double quantityMultiplier,
            boolean enabled,
            long maxSignalAgeSeconds,
            BrokerOrderGateway orderGateway) {
        super(consumerId, "ZERODHA", executionMode, quantityMultiplier, enabled, maxSignalAgeSeconds);
        this.orderGateway = orderGateway;
    }

    @Override
    protected void handleLiveExecution(TradeSignal signal, int quantity) {
        TransactionType txnType =
                (signal.action() == SignalAction.ENTRY_LONG || signal.action() == SignalAction.BUY || signal.action() == SignalAction.EXIT_SHORT)
                        ? TransactionType.BUY
                        : TransactionType.SELL;

        OrderRequest request =
                new OrderRequest(
                        signal.tradingSymbol(),
                        "NFO",
                        txnType,
                        OrderType.MKT,
                        ProductType.MIS,
                        quantity,
                        BigDecimal.ZERO,
                        null,
                        signal.signalId());

        OrderResponse resp = orderGateway.placeOrder(request);
        log.info("[CONSUMER:{}] Zerodha live order placed: {} | Result: {}", getConsumerId(), signal.tradingSymbol(), resp);
    }

    @Override
    protected void handlePaperExecution(TradeSignal signal, int quantity) {
        log.info(
                "[CONSUMER:{}:PAPER] Zerodha Simulated trade executed: {} {} x {} @ {}",
                getConsumerId(),
                signal.action(),
                signal.tradingSymbol(),
                quantity,
                signal.price());
    }
}
```

- [ ] **Step 4: Run tests and verify they pass**

Run: `./gradlew test --tests com.tradingbot.execution.consumer.ShoonyaTradeConsumerTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/tradingbot/execution/ src/test/java/com/tradingbot/execution/
git commit -m "feat: implement Shoonya and Zerodha gateways and consumer adapters"
```

---

### Task 5: Implement Consumer Configuration & Dynamic Consumer Manager

**Files:**
- Create: `src/main/java/com/tradingbot/execution/config/ExecutionProperties.java`
- Create: `src/main/java/com/tradingbot/execution/consumer/TradeConsumerManager.java`
- Modify: `src/main/resources/application.yml`
- Test: `src/test/java/com/tradingbot/execution/consumer/TradeConsumerManagerTest.java`

**Interfaces:**
- Consumes: `SignalStreamProvider`, `ExecutionProperties`, `ShoonyaBrokerGateway`, `ZerodhaBrokerGateway`.
- Produces: `TradeConsumerManager` managing registered consumers, lifecycle startup/shutdown.

- [ ] **Step 1: Write unit test `TradeConsumerManagerTest.java`**

```java
package com.tradingbot.execution.consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.tradingbot.bus.ReactiveSignalEventBus;
import com.tradingbot.execution.config.ExecutionProperties;
import com.tradingbot.execution.gateway.ShoonyaBrokerGateway;
import com.tradingbot.execution.gateway.ZerodhaBrokerGateway;
import com.tradingbot.model.execution.ExecutionMode;
import java.util.List;
import org.junit.jupiter.api.Test;

class TradeConsumerManagerTest {

    @Test
    void testManagerInitializesAndSubscribesConsumers() {
        ExecutionProperties props = new ExecutionProperties();
        ExecutionProperties.ConsumerConfig c1 = new ExecutionProperties.ConsumerConfig();
        c1.setId("shoonya-test");
        c1.setBroker("SHOONYA");
        c1.setMode(ExecutionMode.PAPER);
        c1.setQuantityMultiplier(1.0);
        c1.setEnabled(true);

        ExecutionProperties.ConsumerConfig c2 = new ExecutionProperties.ConsumerConfig();
        c2.setId("zerodha-test");
        c2.setBroker("ZERODHA");
        c2.setMode(ExecutionMode.PAPER);
        c2.setQuantityMultiplier(2.0);
        c2.setEnabled(true);

        props.setConsumers(List.of(c1, c2));

        ReactiveSignalEventBus bus = new ReactiveSignalEventBus();
        ShoonyaBrokerGateway shoonyaGw = mock(ShoonyaBrokerGateway.class);
        ZerodhaBrokerGateway zerodhaGw = mock(ZerodhaBrokerGateway.class);

        TradeConsumerManager manager =
                new TradeConsumerManager(props, bus, shoonyaGw, zerodhaGw);
        manager.init();

        assertEquals(2, manager.getRegisteredConsumers().size());
        assertNotNull(manager.getConsumer("shoonya-test"));
        assertNotNull(manager.getConsumer("zerodha-test"));

        manager.shutdown();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.tradingbot.execution.consumer.TradeConsumerManagerTest`
Expected: FAIL.

- [ ] **Step 3: Implement `ExecutionProperties` and `TradeConsumerManager`**

Create `ExecutionProperties.java`:
```java
package com.tradingbot.execution.config;

import com.tradingbot.model.execution.ExecutionMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "trading-bot.execution")
public class ExecutionProperties {

    private List<ConsumerConfig> consumers = new ArrayList<>();

    public List<ConsumerConfig> getConsumers() {
        return consumers;
    }

    public void setConsumers(List<ConsumerConfig> consumers) {
        this.consumers = consumers;
    }

    public static class ConsumerConfig {
        private String id;
        private String broker = "SHOONYA";
        private ExecutionMode mode = ExecutionMode.PAPER;
        private double quantityMultiplier = 1.0;
        private boolean enabled = true;
        private long maxSignalAgeSeconds = 30;
        private Map<String, String> credentials;

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getBroker() { return broker; }
        public void setBroker(String broker) { this.broker = broker; }
        public ExecutionMode getMode() { return mode; }
        public void setMode(ExecutionMode mode) { this.mode = mode; }
        public double getQuantityMultiplier() { return quantityMultiplier; }
        public void setQuantityMultiplier(double quantityMultiplier) { this.quantityMultiplier = quantityMultiplier; }
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public long getMaxSignalAgeSeconds() { return maxSignalAgeSeconds; }
        public void setMaxSignalAgeSeconds(long maxSignalAgeSeconds) { this.maxSignalAgeSeconds = maxSignalAgeSeconds; }
        public Map<String, String> getCredentials() { return credentials; }
        public void setCredentials(Map<String, String> credentials) { this.credentials = credentials; }
    }
}
```

Create `TradeConsumerManager.java`:
```java
package com.tradingbot.execution.consumer;

import com.tradingbot.bus.SignalStreamProvider;
import com.tradingbot.execution.config.ExecutionProperties;
import com.tradingbot.execution.gateway.ShoonyaBrokerGateway;
import com.tradingbot.execution.gateway.ZerodhaBrokerGateway;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class TradeConsumerManager {

    private static final Logger log = LoggerFactory.getLogger(TradeConsumerManager.class);

    private final ExecutionProperties properties;
    private final SignalStreamProvider signalStreamProvider;
    private final ShoonyaBrokerGateway shoonyaGateway;
    private final ZerodhaBrokerGateway zerodhaGateway;

    private final Map<String, TradeExecutionConsumer> consumers = new ConcurrentHashMap<>();

    public TradeConsumerManager(
            ExecutionProperties properties,
            SignalStreamProvider signalStreamProvider,
            ShoonyaBrokerGateway shoonyaGateway,
            ZerodhaBrokerGateway zerodhaGateway) {
        this.properties = properties;
        this.signalStreamProvider = signalStreamProvider;
        this.shoonyaGateway = shoonyaGateway;
        this.zerodhaGateway = zerodhaGateway;
    }

    @PostConstruct
    public void init() {
        if (properties.getConsumers() == null || properties.getConsumers().isEmpty()) {
            log.info("[CONSUMER-MGR] No execution consumers configured in application.yml");
            return;
        }

        for (ExecutionProperties.ConsumerConfig cfg : properties.getConsumers()) {
            TradeExecutionConsumer consumer = createConsumer(cfg);
            if (consumer != null) {
                consumers.put(consumer.getConsumerId(), consumer);
                consumer.start(signalStreamProvider.getSignalStream());
            }
        }
        log.info("[CONSUMER-MGR] Initialized and started {} execution consumers.", consumers.size());
    }

    private TradeExecutionConsumer createConsumer(ExecutionProperties.ConsumerConfig cfg) {
        String broker = cfg.getBroker() != null ? cfg.getBroker().toUpperCase() : "SHOONYA";
        return switch (broker) {
            case "SHOONYA" -> new ShoonyaTradeConsumer(
                    cfg.getId(),
                    cfg.getMode(),
                    cfg.getQuantityMultiplier(),
                    cfg.isEnabled(),
                    cfg.getMaxSignalAgeSeconds(),
                    shoonyaGateway);
            case "ZERODHA" -> new ZerodhaTradeConsumer(
                    cfg.getId(),
                    cfg.getMode(),
                    cfg.getQuantityMultiplier(),
                    cfg.isEnabled(),
                    cfg.getMaxSignalAgeSeconds(),
                    zerodhaGateway);
            default -> {
                log.warn("[CONSUMER-MGR] Unknown broker '{}' for consumer ID: {}", broker, cfg.getId());
                yield null;
            }
        };
    }

    @PreDestroy
    public void shutdown() {
        log.info("[CONSUMER-MGR] Shutting down execution consumers...");
        consumers.values().forEach(TradeExecutionConsumer::stop);
        consumers.clear();
    }

    public Collection<TradeExecutionConsumer> getRegisteredConsumers() {
        return Collections.unmodifiableCollection(consumers.values());
    }

    public TradeExecutionConsumer getConsumer(String consumerId) {
        return consumers.get(consumerId);
    }
}
```

Update `src/main/resources/application.yml` with default consumer settings:
```yaml
trading-bot:
  execution:
    consumers:
      - id: "shoonya-default"
        broker: "SHOONYA"
        enabled: true
        mode: "PAPER"
        quantity-multiplier: 1.0
        max-signal-age-seconds: 30
```

- [ ] **Step 4: Run tests and verify they pass**

Run: `./gradlew test --tests com.tradingbot.execution.consumer.TradeConsumerManagerTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/tradingbot/execution/config/ src/main/java/com/tradingbot/execution/consumer/ src/test/java/com/tradingbot/execution/consumer/ src/main/resources/application.yml
git commit -m "feat: implement ExecutionProperties and dynamic TradeConsumerManager"
```

---

### Task 6: Decouple Strategy Engines & Remove Legacy Monolithic Execution

**Files:**
- Modify: `src/main/java/com/tradingbot/service/LowestVolumeReversalService.java`
- Delete: `src/main/java/com/tradingbot/execution/ExecutionManager.java` (and delete corresponding legacy test `ExecutionManagerTest.java`)
- Modify: `src/main/java/com/tradingbot/controller/ExecutionController.java`
- Test: `src/test/java/com/tradingbot/service/LowestVolumeReversalServiceTest.java`

**Interfaces:**
- Consumes: `SignalPublisher`, `LowestVolumeReversalScanner`.
- Produces: Pure signal-publishing strategy without monolithic order execution coupling.

- [ ] **Step 1: Update `LowestVolumeReversalService` to publish `TradeSignal`**
Inject `SignalPublisher` into `LowestVolumeReversalService` and publish `TradeSignal.of(...)` on:
- Entry triggered -> publish `SignalAction.ENTRY_LONG` or `SignalAction.ENTRY_SHORT`
- 1:2 RR Target 1 hit -> publish `SignalAction.PARTIAL_EXIT_LONG` / `SignalAction.PARTIAL_EXIT_SHORT`
- Stop Loss / 10 EMA / EOD square-off -> publish `SignalAction.EXIT_LONG` / `SignalAction.EXIT_SHORT`
- Telegram alerts remain active for telegram subscribers.

- [ ] **Step 2: Remove legacy `ExecutionManager` and refactor `ExecutionController`**
- Remove `ExecutionManager.java` and `ExecutionManagerTest.java`.
- Refactor `ExecutionController` to inject `TradeConsumerManager` and expose `/api/v1/execution/consumers` instead of monolithic execution methods.

- [ ] **Step 3: Run all strategy & execution tests**

Run: `./gradlew test`
Expected: PASS

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/tradingbot/service/ src/main/java/com/tradingbot/controller/ src/test/
git commit -m "refactor: decouple LowestVolumeReversalService and remove legacy ExecutionManager"
```

---

### Task 7: REST API Endpoints & Observability

**Files:**
- Create: `src/main/java/com/tradingbot/controller/SignalController.java`
- Modify: `src/main/java/com/tradingbot/controller/ExecutionController.java`
- Test: `src/test/java/com/tradingbot/controller/SignalControllerTest.java`

**Interfaces:**
- Consumes: `SignalPublisher`, `TradeConsumerManager`.
- Produces:
  - `POST /api/v1/signals/publish`: Publish custom/test signal.
  - `GET /api/v1/execution/consumers`: List status of all consumers.

- [ ] **Step 1: Write tests for `SignalControllerTest.java`**

```java
package com.tradingbot.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingbot.bus.SignalPublisher;
import com.tradingbot.strategy.SignalAction;
import com.tradingbot.strategy.TradeSignal;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(SignalController.class)
class SignalControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @MockBean private SignalPublisher signalPublisher;

    @Test
    void testPublishTestSignal() throws Exception {
        when(signalPublisher.publish(any(TradeSignal.class))).thenReturn(true);

        TradeSignal signal =
                TradeSignal.of(
                        "MANUAL",
                        "NIFTY",
                        "NIFTY26MARFUT",
                        SignalAction.ENTRY_LONG,
                        BigDecimal.valueOf(22500),
                        null,
                        null,
                        50,
                        "Manual Test",
                        null);

        mockMvc.perform(
                        post("/api/v1/signals/test")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(signal)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.tradingbot.controller.SignalControllerTest`
Expected: FAIL.

- [ ] **Step 3: Implement `SignalController.java`**

```java
package com.tradingbot.controller;

import com.tradingbot.bus.SignalPublisher;
import com.tradingbot.strategy.TradeSignal;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/signals")
public class SignalController {

    private final SignalPublisher signalPublisher;

    public SignalController(SignalPublisher signalPublisher) {
        this.signalPublisher = signalPublisher;
    }

    @PostMapping("/test")
    public ResponseEntity<Map<String, Object>> publishTestSignal(@RequestBody TradeSignal signal) {
        boolean emitted = signalPublisher.publish(signal);
        return ResponseEntity.ok(
                Map.of(
                        "success", emitted,
                        "signalId", signal != null ? signal.signalId() : "null",
                        "message", emitted ? "Signal broadcasted to all consumers" : "Signal rejected"));
    }
}
```

- [ ] **Step 4: Run tests and verify they pass**

Run: `./gradlew test --tests com.tradingbot.controller.SignalControllerTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/tradingbot/controller/ src/test/java/com/tradingbot/controller/
git commit -m "feat: add SignalController for testing reactive signal distribution"
```

---

### Task 8: End-to-End Integration Verification & Code Standards

**Files:**
- Create: `src/test/java/com/tradingbot/execution/SignalToMultiBrokerIntegrationTest.java`

- [ ] **Step 1: Write end-to-end multi-broker reactive test**

```java
package com.tradingbot.execution;

import static org.junit.jupiter.api.Assertions.*;

import com.tradingbot.bus.ReactiveSignalEventBus;
import com.tradingbot.execution.config.ExecutionProperties;
import com.tradingbot.execution.consumer.TradeConsumerManager;
import com.tradingbot.execution.gateway.ShoonyaBrokerGateway;
import com.tradingbot.execution.gateway.ZerodhaBrokerGateway;
import com.tradingbot.model.execution.ExecutionMode;
import com.tradingbot.model.order.OrderResponse;
import com.tradingbot.strategy.SignalAction;
import com.tradingbot.strategy.TradeSignal;
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class SignalToMultiBrokerIntegrationTest {

    @Test
    void testEndToEndSignalBroadcastAcrossMultipleBrokers() throws InterruptedException {
        ReactiveSignalEventBus bus = new ReactiveSignalEventBus();

        ShoonyaBrokerGateway shoonyaGw = Mockito.mock(ShoonyaBrokerGateway.class);
        ZerodhaBrokerGateway zerodhaGw = Mockito.mock(ZerodhaBrokerGateway.class);

        AtomicInteger shoonyaOrders = new AtomicInteger(0);
        AtomicInteger zerodhaOrders = new AtomicInteger(0);

        Mockito.when(shoonyaGw.placeOrder(Mockito.any())).thenAnswer(inv -> {
            shoonyaOrders.incrementAndGet();
            return new OrderResponse("SH_1", true, "Shoonya OK", "COMPLETE");
        });

        Mockito.when(zerodhaGw.placeOrder(Mockito.any())).thenAnswer(inv -> {
            zerodhaOrders.incrementAndGet();
            return new OrderResponse("ZH_1", true, "Zerodha OK", "COMPLETE");
        });

        ExecutionProperties props = new ExecutionProperties();
        ExecutionProperties.ConsumerConfig shoonyaCfg = new ExecutionProperties.ConsumerConfig();
        shoonyaCfg.setId("shoonya-live");
        shoonyaCfg.setBroker("SHOONYA");
        shoonyaCfg.setMode(ExecutionMode.LIVE);
        shoonyaCfg.setQuantityMultiplier(1.0);
        shoonyaCfg.setEnabled(true);

        ExecutionProperties.ConsumerConfig zerodhaCfg = new ExecutionProperties.ConsumerConfig();
        zerodhaCfg.setId("zerodha-live");
        zerodhaCfg.setBroker("ZERODHA");
        zerodhaCfg.setMode(ExecutionMode.LIVE);
        zerodhaCfg.setQuantityMultiplier(2.0);
        zerodhaCfg.setEnabled(true);

        props.setConsumers(List.of(shoonyaCfg, zerodhaCfg));

        TradeConsumerManager manager = new TradeConsumerManager(props, bus, shoonyaGw, zerodhaGw);
        manager.init();

        TradeSignal signal =
                TradeSignal.of(
                        "LVR_FUTURES",
                        "RELIANCE",
                        "RELIANCE26MARFUT",
                        SignalAction.ENTRY_LONG,
                        BigDecimal.valueOf(2500),
                        BigDecimal.valueOf(2480),
                        BigDecimal.valueOf(2540),
                        250,
                        "Breakout",
                        null);

        bus.publish(signal);
        Thread.sleep(200);

        assertEquals(1, shoonyaOrders.get());
        assertEquals(1, zerodhaOrders.get());

        manager.shutdown();
    }
}
```

- [ ] **Step 2: Run Spotless & SpotBugs and full test suite**

Run:
```bash
./gradlew spotlessApply
./gradlew spotbugsMain spotbugsTest
./gradlew test
```
Expected: ALL PASS with zero format issues and zero SpotBugs errors.

- [ ] **Step 3: Commit**

```bash
git add .
git commit -m "test: add end-to-end multi-broker reactive signal distribution test"
```
