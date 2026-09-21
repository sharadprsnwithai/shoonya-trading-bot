package com.tradingbot.positional.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.tradingbot.model.execution.ExecutionMode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

/** Represents an active or historical positional hedged credit spread trade. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PositionalTrade(
        @JsonProperty("tradeId") String tradeId,
        @JsonProperty("underlying") String underlying,
        @JsonProperty("strategyType") String strategyType,
        @JsonProperty("sellOptionType") String sellOptionType,
        @JsonProperty("sellStrike") BigDecimal sellStrike,
        @JsonProperty("buyHedgeOptionType") String buyHedgeOptionType,
        @JsonProperty("buyHedgeStrike") BigDecimal buyHedgeStrike,
        @JsonProperty("expiryDate") String expiryDate,
        @JsonProperty("entryDate") LocalDate entryDate,
        @JsonProperty("entrySpot") BigDecimal entrySpot,
        @JsonProperty("sellEntryPremium") BigDecimal sellEntryPremium,
        @JsonProperty("buyHedgeEntryPremium") BigDecimal buyHedgeEntryPremium,
        @JsonProperty("netCredit") BigDecimal netCredit,
        @JsonProperty("slSpot") BigDecimal slSpot,
        @JsonProperty("targetSpot") BigDecimal targetSpot,
        @JsonProperty("numLots") int numLots,
        @JsonProperty("quantity") int quantity,
        @JsonProperty("mode") ExecutionMode mode,
        @JsonProperty("status") String status,
        @JsonProperty("exitDate") LocalDate exitDate,
        @JsonProperty("exitSpot") BigDecimal exitSpot,
        @JsonProperty("sellExitPremium") BigDecimal sellExitPremium,
        @JsonProperty("buyHedgeExitPremium") BigDecimal buyHedgeExitPremium,
        @JsonProperty("pnl") BigDecimal pnl,
        @JsonProperty("exitReason") String exitReason) {

    /** Helper for single-leg or backwards compatibility. */
    public String optionType() {
        return sellOptionType != null ? sellOptionType : "PE";
    }

    /** Helper for strike price. */
    public BigDecimal strikePrice() {
        return sellStrike != null ? sellStrike : BigDecimal.ZERO;
    }

    /** Helper for entry premium. */
    public BigDecimal entryPremium() {
        return netCredit != null
                ? netCredit
                : (sellEntryPremium != null ? sellEntryPremium : BigDecimal.ZERO);
    }

    /** Helper for exit premium. */
    public BigDecimal exitPremium() {
        if (sellExitPremium != null && buyHedgeExitPremium != null) {
            return sellExitPremium.subtract(buyHedgeExitPremium).setScale(2, RoundingMode.HALF_UP);
        }
        return BigDecimal.ZERO;
    }
}
