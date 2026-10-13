package com.tradingbot.marketdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingbot.auth.ShoonyaAuthenticator;
import com.tradingbot.config.ShoonyaConfig;
import com.tradingbot.model.OptionChainResponse;
import java.math.BigDecimal;
import java.net.http.HttpClient;
import org.junit.jupiter.api.Test;

class ShoonyaOptionChainServiceTest {

    @Test
    void getNifty50OptionChain_UsesResolvedLiveFuturesContract() {
        ShoonyaMarketDataService marketDataService = mock(ShoonyaMarketDataService.class);
        when(marketDataService.resolveFuturesContract("NIFTY"))
                .thenReturn(new ShoonyaMarketDataService.FuturesContract("NIFTY27OCT26F", "68408"));

        CapturingOptionChainService service = capturingService(marketDataService);
        service.getNifty50OptionChain(new BigDecimal("25000"), 4, true);

        assertThat(service.capturedUnderlying).isEqualTo("NIFTY");
        assertThat(service.capturedFutSymbol).isEqualTo("NIFTY27OCT26F");
        assertThat(service.capturedFutToken).isEqualTo("68408");
        assertThat(service.capturedStrike).isEqualByComparingTo(new BigDecimal("25000"));
    }

    @Test
    void getNifty50OptionChain_FallsBackToConstantsWhenResolutionFails() {
        ShoonyaMarketDataService marketDataService = mock(ShoonyaMarketDataService.class);
        when(marketDataService.resolveFuturesContract("NIFTY")).thenReturn(null);

        CapturingOptionChainService service = capturingService(marketDataService);
        service.getNifty50OptionChain(null, 2, true);

        assertThat(service.capturedFutSymbol)
                .isEqualTo(ShoonyaOptionChainService.DEFAULT_NIFTY_FUT_SYMBOL);
        assertThat(service.capturedFutToken)
                .isEqualTo(ShoonyaOptionChainService.DEFAULT_NIFTY_FUT_TOKEN);
    }

    @Test
    void getNifty50OptionChain_FallsBackToConstantsWhenMarketDataServiceAbsent() {
        CapturingOptionChainService service = capturingService(null);
        service.getNifty50OptionChain(null, 2, false);

        assertThat(service.capturedFutSymbol)
                .isEqualTo(ShoonyaOptionChainService.DEFAULT_NIFTY_FUT_SYMBOL);
        assertThat(service.capturedFutToken)
                .isEqualTo(ShoonyaOptionChainService.DEFAULT_NIFTY_FUT_TOKEN);
    }

    @Test
    void getOptionChain_WhenDisabled_FlagsChainSyntheticWithPcrExactlyOne() {
        ShoonyaConfig config = new ShoonyaConfig();
        config.setEnabled(false);
        ShoonyaOptionChainService service =
                new ShoonyaOptionChainService(
                        config,
                        mock(ShoonyaAuthenticator.class),
                        mock(ShoonyaMarketDataService.class),
                        new ObjectMapper(),
                        HttpClient.newHttpClient());

        for (int count : new int[] {4, 5}) {
            OptionChainResponse chain =
                    service.getOptionChain("TCS", "TCS", "", new BigDecimal("2240"), count, true);

            assertThat(chain.isSynthetic())
                    .as("fallback chain must be flagged synthetic (count=%d)", count)
                    .isTrue();
            assertThat(chain.totalCallOi())
                    .as("mirrored OI ladders are always equal (count=%d)", count)
                    .isEqualTo(chain.totalPutOi());
            assertThat(chain.pcr())
                    .as("fabricated PCR is always exactly 1.00 (count=%d)", count)
                    .isEqualTo(1.00);
        }
    }

    private static CapturingOptionChainService capturingService(
            ShoonyaMarketDataService marketDataService) {
        return new CapturingOptionChainService(
                new ShoonyaConfig(),
                mock(ShoonyaAuthenticator.class),
                marketDataService,
                new ObjectMapper(),
                HttpClient.newHttpClient());
    }

    /** Records getOptionChain arguments instead of performing network calls. */
    private static class CapturingOptionChainService extends ShoonyaOptionChainService {
        String capturedUnderlying;
        String capturedFutSymbol;
        String capturedFutToken;
        BigDecimal capturedStrike;

        CapturingOptionChainService(
                ShoonyaConfig config,
                ShoonyaAuthenticator authenticator,
                ShoonyaMarketDataService marketDataService,
                ObjectMapper objectMapper,
                HttpClient httpClient) {
            super(config, authenticator, marketDataService, objectMapper, httpClient);
        }

        @Override
        public OptionChainResponse getOptionChain(
                String underlying,
                String futSymbol,
                String futToken,
                BigDecimal explicitStrike,
                int count,
                boolean fetchQuotes) {
            this.capturedUnderlying = underlying;
            this.capturedFutSymbol = futSymbol;
            this.capturedFutToken = futToken;
            this.capturedStrike = explicitStrike;
            return null;
        }
    }
}
