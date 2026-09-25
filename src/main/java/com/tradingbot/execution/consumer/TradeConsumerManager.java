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
import org.springframework.beans.factory.annotation.Autowired;
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
                            cfg.getQuantityMultiplier(),
                            cfg.isEnabled(),
                            cfg.getMaxSignalAgeSeconds(),
                            shoonyaGateway);
            case "ZERODHA" ->
                    new ZerodhaTradeConsumer(
                            cfg.getId(),
                            cfg.getMode(),
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
}
