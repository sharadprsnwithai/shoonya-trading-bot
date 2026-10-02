package com.tradingbot.execution.consumer;

import com.tradingbot.bus.SignalStreamProvider;
import com.tradingbot.execution.config.ExecutionProperties;
import com.tradingbot.execution.gateway.BrokerOrderGateway;
import com.tradingbot.execution.gateway.ShoonyaBrokerGateway;
import com.tradingbot.execution.gateway.ZerodhaBrokerGateway;
import com.tradingbot.model.execution.BrokerPosition;
import com.tradingbot.model.execution.ExecutionMode;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class TradeConsumerManager {

    private static final Logger log = LoggerFactory.getLogger(TradeConsumerManager.class);

    private final ExecutionProperties properties;
    private final SignalStreamProvider signalStreamProvider;
    private final ShoonyaBrokerGateway shoonyaGateway;
    private final ZerodhaBrokerGateway zerodhaGateway;

    private final Map<String, TradeExecutionConsumer> consumers = new ConcurrentHashMap<>();

    @Autowired
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
        // H2: more than one LIVE consumer means every signal becomes duplicate live orders
        // (2x exposure; exits can land on the broker that never took the entry).
        long liveConsumers =
                consumers.values().stream()
                        .filter(TradeExecutionConsumer::isEnabled)
                        .filter(
                                c ->
                                        c.getExecutionMode()
                                                        == com.tradingbot.model.execution.ExecutionMode
                                                                .LIVE
                                                || c.getStrategyModes().containsValue(
                                                        com.tradingbot.model.execution.ExecutionMode
                                                                .LIVE))
                        .count();
        if (liveConsumers > 1) {
            log.warn(
                    "[CONSUMER-MGR] ⚠️ {} consumers are enabled with LIVE execution. Every"
                            + " ENTRY/EXIT will be ordered at MULTIPLE brokers (duplicate"
                            + " exposure). Disable all but one consumer (or route via"
                            + " strategy-modes) unless dual-broker execution is intentional.",
                    liveConsumers);
        }
        log.info(
                "[CONSUMER-MGR] Initialized and started {} execution consumers.", consumers.size());
    }

    private TradeExecutionConsumer createConsumer(ExecutionProperties.ConsumerConfig cfg) {
        String broker = cfg.getBroker() != null ? cfg.getBroker().toUpperCase() : "SHOONYA";
        return switch (broker) {
            case "SHOONYA" ->
                    new ShoonyaTradeConsumer(
                            cfg.getId(),
                            cfg.getMode(),
                            cfg.getStrategyModes(),
                            cfg.getQuantityMultiplier(),
                            cfg.isEnabled(),
                            cfg.getMaxSignalAgeSeconds(),
                            shoonyaGateway);
            case "ZERODHA" ->
                    new ZerodhaTradeConsumer(
                            cfg.getId(),
                            cfg.getMode(),
                            cfg.getStrategyModes(),
                            cfg.getQuantityMultiplier(),
                            cfg.isEnabled(),
                            cfg.getMaxSignalAgeSeconds(),
                            zerodhaGateway);
            default -> {
                log.warn(
                        "[CONSUMER-MGR] Unknown broker '{}' for consumer ID: {}",
                        broker,
                        cfg.getId());
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

    /**
     * C3: broker drift reconciliation. Compares each LIVE consumer's confirmed-entry ledger
     * against actual broker positions and alerts on divergence:
     *
     * <ul>
     *   <li>Broker position with no confirmed ENTRY → manual/external position or failed fill
     *       bookkeeping (ERROR, or INFO while the ledger is empty after a fresh start).
     *   <li>Confirmed ENTRY with no open broker position → fill never happened or position was
     *       closed outside the bot (WARN).
     * </ul>
     *
     * Skipped when more than one LIVE consumer is enabled (ownership is ambiguous — H2 already
     * warned).
     */
    @Scheduled(
            initialDelayString =
                    "${trading-bot.execution.drift-reconcile-initial-ms:60000}",
            fixedDelayString =
                    "${trading-bot.execution.drift-reconcile-interval-ms:300000}")
    public void reconcileBrokerDrift() {
        List<TradeExecutionConsumer> live =
                consumers.values().stream()
                        .filter(TradeExecutionConsumer::isEnabled)
                        .filter(this::isLive)
                        .toList();
        if (live.isEmpty() || live.size() > 1) {
            return;
        }
        TradeExecutionConsumer consumer = live.get(0);
        BrokerOrderGateway gateway = gatewayFor(consumer.getBrokerName());
        if (gateway == null) {
            return;
        }
        try {
            List<BrokerPosition> openPositions =
                    gateway.getPositions().stream().filter(p -> p.quantity() != 0).toList();

            Set<String> confirmed = consumer.getConfirmedEntrySymbols();
            for (BrokerPosition pos : openPositions) {
                String brokerSymbol = firstNonBlank(pos.tradingSymbol(), pos.symbol());
                if (brokerSymbol == null) {
                    continue;
                }
                String upper = brokerSymbol.toUpperCase();
                boolean owned =
                        confirmed.stream().anyMatch(k -> upper.startsWith(k.toUpperCase()));
                if (!owned) {
                    if (confirmed.isEmpty()) {
                        log.info(
                                "[CONSUMER-MGR] Broker position {} not in (empty)"
                                        + " confirmed-entry ledger — fresh start or"
                                        + " manual/external position.",
                                brokerSymbol);
                    } else {
                        log.error(
                                "[CONSUMER-MGR] ⚠️ DRIFT: broker position {} has NO confirmed"
                                        + " ENTRY in consumer {} — manual/external position or"
                                        + " failed fill bookkeeping. Inspect immediately.",
                                brokerSymbol,
                                consumer.getConsumerId());
                    }
                }
            }

            List<String> brokerSymbols =
                    openPositions.stream()
                            .map(p -> firstNonBlank(p.tradingSymbol(), p.symbol()))
                            .filter(s -> s != null)
                            .map(String::toUpperCase)
                            .toList();
            for (String key : confirmed) {
                String upper = key.toUpperCase();
                boolean present = brokerSymbols.stream().anyMatch(s -> s.startsWith(upper));
                if (!present) {
                    log.warn(
                            "[CONSUMER-MGR] Confirmed ENTRY {} not present as an open broker"
                                    + " position in {} — fill may have failed or the position"
                                    + " was closed outside the bot.",
                            key,
                            consumer.getConsumerId());
                }
            }
        } catch (Exception e) {
            log.warn("[CONSUMER-MGR] Broker drift reconciliation failed: {}", e.getMessage());
        }
    }

    private boolean isLive(TradeExecutionConsumer consumer) {
        return consumer.getExecutionMode() == ExecutionMode.LIVE
                || consumer.getStrategyModes().containsValue(ExecutionMode.LIVE);
    }

    private BrokerOrderGateway gatewayFor(String brokerName) {
        if (brokerName == null) {
            return null;
        }
        return switch (brokerName.toUpperCase()) {
            case "SHOONYA" -> shoonyaGateway;
            case "ZERODHA" -> zerodhaGateway;
            default -> null;
        };
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        return (b != null && !b.isBlank()) ? b : null;
    }
}
