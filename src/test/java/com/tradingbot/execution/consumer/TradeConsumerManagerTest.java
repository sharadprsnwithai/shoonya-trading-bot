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

        TradeConsumerManager manager = new TradeConsumerManager(props, bus, shoonyaGw, zerodhaGw);
        manager.init();

        assertEquals(2, manager.getRegisteredConsumers().size());
        assertNotNull(manager.getConsumer("shoonya-test"));
        assertNotNull(manager.getConsumer("zerodha-test"));

        manager.shutdown();
    }
}
