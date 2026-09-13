package com.tradingbot.positional.execution;

import com.tradingbot.positional.model.PositionalTrade;
import java.math.BigDecimal;

/** Service responsible for executing positional option orders (ATM Selling + 0.20 Delta Hedge). */
public interface PositionalExecutionService {

    /**
     * Executes hedged option entry for the staged trade (BULL_PUT_SPREAD or BEAR_CALL_SPREAD).
     *
     * @param trade Staged trade details.
     * @return Executed trade with fill details (entry premiums, net credit, etc.).
     */
    PositionalTrade executeEntry(PositionalTrade trade);

    /**
     * Executes option exit (square-off) for both legs of the active spread.
     *
     * @param trade Active trade details.
     * @param exitSpot Current spot index level.
     * @param exitReason Reason for exit (STOP_LOSS, TARGET_OPPOSITE_BAND, MANUAL, EXPIRY_ROLLOVER).
     * @return Completed trade record with exit fill details and final PnL.
     */
    PositionalTrade executeExit(PositionalTrade trade, BigDecimal exitSpot, String exitReason);

    /**
     * Finds the nearest Monthly ATM Option Strike.
     *
     * @param symbol Underlying index symbol (e.g. "NIFTY 50").
     * @param spotPrice Current spot price.
     * @return Strike price.
     */
    BigDecimal findMonthlyAtmStrike(String symbol, BigDecimal spotPrice);
}
