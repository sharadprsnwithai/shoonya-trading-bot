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
        AtomicInteger shoonyaTotalQty = new AtomicInteger(0);
        AtomicInteger zerodhaTotalQty = new AtomicInteger(0);

        Mockito.when(shoonyaGw.placeOrder(Mockito.any()))
                .thenAnswer(
                        inv -> {
                            com.tradingbot.model.order.OrderRequest req = inv.getArgument(0);
                            shoonyaOrders.incrementAndGet();
                            shoonyaTotalQty.addAndGet(req.quantity());
                            return OrderResponse.success("SH_1", req, "Shoonya OK");
                        });

        Mockito.when(zerodhaGw.placeOrder(Mockito.any()))
                .thenAnswer(
                        inv -> {
                            com.tradingbot.model.order.OrderRequest req = inv.getArgument(0);
                            zerodhaOrders.incrementAndGet();
                            zerodhaTotalQty.addAndGet(req.quantity());
                            return OrderResponse.success("ZH_1", req, "Zerodha OK");
                        });

        ExecutionProperties props = new ExecutionProperties();
        ExecutionProperties.ConsumerConfig shoonyaCfg = new ExecutionProperties.ConsumerConfig();
        shoonyaCfg.setId("shoonya-live");
        shoonyaCfg.setBroker("SHOONYA");
        shoonyaCfg.setMode(ExecutionMode.LIVE);
        shoonyaCfg.setQuantityMultiplier(1.0); // 1.0x -> 250 qty
        shoonyaCfg.setEnabled(true);

        ExecutionProperties.ConsumerConfig zerodhaCfg = new ExecutionProperties.ConsumerConfig();
        zerodhaCfg.setId("zerodha-live");
        zerodhaCfg.setBroker("ZERODHA");
        zerodhaCfg.setMode(ExecutionMode.LIVE);
        zerodhaCfg.setQuantityMultiplier(2.0); // 2.0x -> 500 qty
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

        // Verify both brokers executed orders concurrently with their respective multipliers
        assertEquals(1, shoonyaOrders.get());
        assertEquals(250, shoonyaTotalQty.get());

        assertEquals(1, zerodhaOrders.get());
        assertEquals(500, zerodhaTotalQty.get());

        // Emit Exit Signal
        TradeSignal exitSignal =
                TradeSignal.of(
                        "LVR_FUTURES",
                        "RELIANCE",
                        "RELIANCE26MARFUT",
                        SignalAction.EXIT_LONG,
                        BigDecimal.valueOf(2540),
                        null,
                        null,
                        250,
                        "Target Hit",
                        null);

        bus.publish(exitSignal);
        Thread.sleep(200);

        assertEquals(2, shoonyaOrders.get());
        assertEquals(500, shoonyaTotalQty.get());

        assertEquals(2, zerodhaOrders.get());
        assertEquals(1000, zerodhaTotalQty.get());

        manager.shutdown();
    }
}
