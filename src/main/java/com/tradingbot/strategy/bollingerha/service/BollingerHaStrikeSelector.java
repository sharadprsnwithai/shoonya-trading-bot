package com.tradingbot.strategy.bollingerha.service;

import com.tradingbot.marketdata.ShoonyaOptionChainService;
import com.tradingbot.model.OptionChainResponse;
import com.tradingbot.model.OptionStrike;
import com.tradingbot.strategy.bollingerha.model.SelectedStrikes;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Resolves the weekly ATM CE and PE option contracts for the Bollinger HA strategy. */
@Service
public class BollingerHaStrikeSelector {

    private static final Logger log = LoggerFactory.getLogger(BollingerHaStrikeSelector.class);
    private static final BigDecimal FIFTY = new BigDecimal("50");

    private final ShoonyaOptionChainService optionChainService;

    @Autowired
    public BollingerHaStrikeSelector(ShoonyaOptionChainService optionChainService) {
        this.optionChainService = optionChainService;
    }

    /**
     * Calculates ATM strike rounded to nearest 50 and resolves weekly CE & PE contracts.
     *
     * @param spotPrice Current or pre-market Nifty 50 spot price
     * @return SelectedStrikes containing ATM CE & PE token details
     */
    public SelectedStrikes selectWeeklyAtmStrikes(BigDecimal spotPrice) {
        if (spotPrice == null || spotPrice.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Invalid spot price: " + spotPrice);
        }

        BigDecimal atmStrike =
                spotPrice.divide(FIFTY, 0, RoundingMode.HALF_UP).multiply(FIFTY).setScale(0);

        log.info(
                "[BOLLINGER-HA] Resolving ATM strike for spot {}: Calculated ATM = {}",
                spotPrice,
                atmStrike);

        OptionChainResponse chainResp =
                optionChainService.getNifty50OptionChain(atmStrike, 1, false);
        if (chainResp == null || chainResp.strikes() == null || chainResp.strikes().isEmpty()) {
            throw new IllegalStateException(
                    "Failed to fetch option chain for ATM strike: " + atmStrike);
        }

        OptionStrike targetStrike =
                chainResp.strikes().stream()
                        .filter(s -> s.strikePrice().compareTo(atmStrike) == 0)
                        .findFirst()
                        .orElse(chainResp.strikes().get(0));

        if (targetStrike.call() == null || targetStrike.put() == null) {
            throw new IllegalStateException(
                    "Option chain at strike " + atmStrike + " is missing Call or Put contract");
        }

        String ceToken = targetStrike.call().token();
        String ceSymbol = targetStrike.call().symbol();
        String peToken = targetStrike.put().token();
        String peSymbol = targetStrike.put().symbol();

        log.info(
                "[BOLLINGER-HA] Resolved strikes - CE: {} ({}), PE: {} ({})",
                ceSymbol,
                ceToken,
                peSymbol,
                peToken);

        return new SelectedStrikes(spotPrice, atmStrike, ceToken, ceSymbol, peToken, peSymbol);
    }
}
