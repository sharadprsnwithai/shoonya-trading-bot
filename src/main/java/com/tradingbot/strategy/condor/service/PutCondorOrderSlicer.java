package com.tradingbot.strategy.condor.service;

import com.tradingbot.execution.gateway.ZerodhaBrokerGateway;
import com.tradingbot.model.execution.ExecutionMode;
import com.tradingbot.model.order.OrderRequest;
import com.tradingbot.model.order.OrderResponse;
import com.tradingbot.model.order.OrderType;
import com.tradingbot.model.order.ProductType;
import com.tradingbot.model.order.TransactionType;
import com.tradingbot.strategy.condor.config.MonthlyPutCondorProperties;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Handles NSE freeze limit order slicing (> 1800 qty) and margin-prioritized order execution for
 * the Monthly Put Condor strategy.
 */
@Service
public class PutCondorOrderSlicer {

    private static final Logger log = LoggerFactory.getLogger(PutCondorOrderSlicer.class);

    private final ZerodhaBrokerGateway zerodhaBrokerGateway;
    private final MonthlyPutCondorProperties properties;

    @Autowired
    public PutCondorOrderSlicer(
            ZerodhaBrokerGateway zerodhaBrokerGateway, MonthlyPutCondorProperties properties) {
        this.zerodhaBrokerGateway = zerodhaBrokerGateway;
        this.properties = properties;
    }

    /** Represents a single leg order specification before slicing. */
    public record LegOrder(
            String tradingsymbol,
            TransactionType transactionType,
            int quantity,
            BigDecimal referencePrice) {}

    /**
     * Slices a total quantity into sub-orders that are each <= maxFreezeLimit and strictly
     * multiples of lotSize.
     *
     * @param totalQuantity total order quantity (e.g., 3250)
     * @param lotSize instrument lot size (e.g., 65)
     * @param maxFreezeLimit max quantity per slice (e.g., 1800)
     * @return list of integer slice quantities summing exactly to totalQuantity
     */
    public List<Integer> calculateSlices(int totalQuantity, int lotSize, int maxFreezeLimit) {
        if (totalQuantity <= 0) {
            return List.of();
        }
        if (totalQuantity <= maxFreezeLimit) {
            return List.of(totalQuantity);
        }

        int totalLots = totalQuantity / lotSize;
        int maxLotsPerSlice = maxFreezeLimit / lotSize;
        int numSlices = (int) Math.ceil((double) totalLots / maxLotsPerSlice);

        int baseLotsPerSlice = totalLots / numSlices;
        int remainderLots = totalLots % numSlices;

        List<Integer> slices = new ArrayList<>();
        for (int i = 0; i < numSlices; i++) {
            int lotsInThisSlice = baseLotsPerSlice + (i < remainderLots ? 1 : 0);
            slices.add(lotsInThisSlice * lotSize);
        }
        return slices;
    }

    /**
     * Executes a list of leg orders with order slicing and margin sequencing. BUY orders are
     * submitted first, followed by SELL orders.
     *
     * @param orders list of LegOrders to execute
     * @param mode PAPER or LIVE
     * @return true if all orders executed successfully
     */
    public boolean executeLegOrders(List<LegOrder> orders, ExecutionMode mode) {
        if (orders == null || orders.isEmpty()) {
            return true;
        }

        // Separate into BUYs first, then SELLs (Margin benefit)
        List<LegOrder> buyOrders =
                orders.stream().filter(o -> o.transactionType() == TransactionType.BUY).toList();

        List<LegOrder> sellOrders =
                orders.stream().filter(o -> o.transactionType() == TransactionType.SELL).toList();

        List<LegOrder> prioritizedOrders = new ArrayList<>(buyOrders);
        prioritizedOrders.addAll(sellOrders);

        int lotSize = properties.getLotSize() > 0 ? properties.getLotSize() : 65;
        int maxFreezeLimit =
                properties.getMaxFreezeLimit() > 0 ? properties.getMaxFreezeLimit() : 1800;

        for (LegOrder order : prioritizedOrders) {
            List<Integer> slices = calculateSlices(order.quantity(), lotSize, maxFreezeLimit);
            log.info(
                    "Executing {} {} for {} (Sliced into {} order(s): {}) in mode {}",
                    order.transactionType(),
                    order.quantity(),
                    order.tradingsymbol(),
                    slices.size(),
                    slices,
                    mode);

            for (int sliceQty : slices) {
                if (mode == ExecutionMode.PAPER) {
                    log.info(
                            "[PAPER] Simulated fill: {} {} {} @ ₹{}",
                            order.transactionType(),
                            sliceQty,
                            order.tradingsymbol(),
                            order.referencePrice());
                } else {
                    OrderRequest req =
                            new OrderRequest(
                                    order.tradingsymbol(),
                                    "NFO",
                                    order.transactionType(),
                                    OrderType.LMT,
                                    ProductType.NRML,
                                    sliceQty,
                                    order.referencePrice(),
                                    BigDecimal.ZERO,
                                    "DAY");
                    OrderResponse resp =
                            zerodhaBrokerGateway.placeOrderWithReferencePrice(
                                    req, order.referencePrice());
                    if (resp == null || !resp.success()) {
                        String errMsg =
                                resp != null ? resp.message() : "Null response from broker gateway";
                        log.error(
                                "[LIVE] Failed to place order slice for {}: {}",
                                order.tradingsymbol(),
                                errMsg);
                        return false;
                    }
                    log.info(
                            "[LIVE] Order slice executed: ID={}, Symbol={}, Qty={}",
                            resp.orderId(),
                            order.tradingsymbol(),
                            sliceQty);
                    try {
                        Thread.sleep(200); // 200ms spacing between slices
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }
        return true;
    }
}
