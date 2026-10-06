package com.tradingbot.execution.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.tradingbot.execution.consumer.ShoonyaTradeConsumer;
import com.tradingbot.model.execution.ExecutionMode;
import com.tradingbot.service.LowestVolumeReversalService;
import com.tradingbot.strategy.SignalAction;
import com.tradingbot.strategy.TradeSignal;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.Properties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.mock.env.MockEnvironment;

/**
 * H1: the strategy id published by {@link LowestVolumeReversalService} must match the {@code
 * strategy-modes.*} keys in the real {@code application.properties}, and per-strategy mode
 * overrides (including {@code SHOONYA_LVR_MODE}) must survive binding all the way into {@code
 * resolveMode(signal)}. This is the integration test the bug report asked for: it binds the shipped
 * properties file itself, not hand-built maps.
 */
class StrategyModeFromApplicationPropertiesTest {

    private static final String LVR_KEY_PREFIX =
            "trading-bot.execution.consumers[0].strategy-modes.";

    private Properties loadApplicationProperties() {
        // Read the SHIPPED main file directly — src/test/resources/application.properties
        // shadows it on the test classpath (test-profile overrides only).
        Properties props = new Properties();
        java.nio.file.Path mainProps =
                java.nio.file.Path.of("src", "main", "resources", "application.properties");
        try (InputStream in = java.nio.file.Files.newInputStream(mainProps)) {
            props.load(in);
        } catch (Exception e) {
            throw new AssertionError("Failed to load " + mainProps.toAbsolutePath(), e);
        }
        assertThat(props.stringPropertyNames())
                .as("main application.properties must contain the execution consumers block")
                .anyMatch(k -> k.startsWith("trading-bot.execution.consumers[0]."));
        return props;
    }

    private ExecutionProperties bind(Properties props, String... overrides) {
        MockEnvironment env = new MockEnvironment();
        // The shipped properties are first in precedence; test overrides go ahead of them.
        env.getPropertySources().addFirst(new PropertiesPropertySource("application", props));
        if (overrides.length > 0) {
            Properties over = new Properties();
            for (String override : overrides) {
                int eq = override.indexOf('=');
                over.setProperty(override.substring(0, eq), override.substring(eq + 1));
            }
            env.getPropertySources().addFirst(new PropertiesPropertySource("overrides", over));
        }
        return Binder.get(env).bind("trading-bot.execution", ExecutionProperties.class).get();
    }

    @Test
    @DisplayName("Published STRATEGY_ID exists as a strategy-modes key in application.properties")
    void strategyIdMatchesApplicationPropertiesKey() {
        assertThat(LowestVolumeReversalService.STRATEGY_ID).isEqualTo("LOWEST_VOLUME_REVERSAL");
        assertThat(loadApplicationProperties().stringPropertyNames())
                .contains(LVR_KEY_PREFIX + LowestVolumeReversalService.STRATEGY_ID);
    }

    @Test
    @DisplayName("resolveMode honors the per-strategy LVR mode bound from application.properties")
    void resolveModeHonorsPerStrategyOverride() {
        Properties props = loadApplicationProperties();
        ExecutionProperties bound = bind(props);
        ExecutionProperties.ConsumerConfig shoonya = bound.getConsumers().get(0);

        assertThat(shoonya.getId()).isEqualTo("shoonya-primary");
        assertThat(shoonya.getBroker()).isEqualTo("SHOONYA");
        assertThat(shoonya.getStrategyModes()).containsKey(LowestVolumeReversalService.STRATEGY_ID);
        // No env override → the nested default chain resolves to PAPER.
        assertThat(shoonya.getStrategyModes().get(LowestVolumeReversalService.STRATEGY_ID))
                .isEqualTo(ExecutionMode.PAPER);

        ShoonyaTradeConsumer consumer =
                new ShoonyaTradeConsumer(
                        shoonya.getId(),
                        shoonya.getMode(),
                        shoonya.getStrategyModes(),
                        shoonya.getQuantityMultiplier(),
                        shoonya.isEnabled(),
                        shoonya.getMaxSignalAgeSeconds(),
                        null);

        TradeSignal lvrSignal =
                TradeSignal.of(
                        LowestVolumeReversalService.STRATEGY_ID,
                        "RELIANCE",
                        "RELIANCE26MARFUT",
                        SignalAction.ENTRY_LONG,
                        BigDecimal.valueOf(2500),
                        BigDecimal.valueOf(2480),
                        BigDecimal.valueOf(2540),
                        250,
                        "Entry",
                        null);
        assertThat(consumer.resolveMode(lvrSignal)).isEqualTo(ExecutionMode.PAPER);

        // Unknown strategy id falls back to the consumer-wide mode.
        TradeSignal otherSignal =
                TradeSignal.of(
                        "SOME_OTHER_STRATEGY",
                        "RELIANCE",
                        "RELIANCE26MARFUT",
                        SignalAction.ENTRY_LONG,
                        BigDecimal.valueOf(2500),
                        null,
                        null,
                        250,
                        "Entry",
                        null);
        assertThat(consumer.resolveMode(otherSignal)).isEqualTo(shoonya.getMode());
    }

    @Test
    @DisplayName("SHOONYA_LVR_MODE=LIVE env override selects LIVE only for the LVR strategy id")
    void shoyonaLvrEnvOverrideSelectsLiveMode() {
        Properties props = loadApplicationProperties();
        ExecutionProperties bound = bind(props, "SHOONYA_LVR_MODE=LIVE");

        ExecutionProperties.ConsumerConfig shoonya = bound.getConsumers().get(0);
        assertThat(shoonya.getStrategyModes().get(LowestVolumeReversalService.STRATEGY_ID))
                .isEqualTo(ExecutionMode.LIVE);
        // Consumer-wide mode is untouched by the per-strategy override.
        assertThat(shoonya.getMode()).isEqualTo(ExecutionMode.PAPER);
        // Other strategies keep the default chain.
        assertThat(shoonya.getStrategyModes().get("CAR_WEEKLY_GTT")).isEqualTo(ExecutionMode.PAPER);

        // Zerodha consumer is bound from its own env chain and defaults to disabled/PAPER.
        ExecutionProperties.ConsumerConfig zerodha = bound.getConsumers().get(1);
        assertThat(zerodha.getBroker()).isEqualTo("ZERODHA");
        assertThat(zerodha.getStrategyModes().get(LowestVolumeReversalService.STRATEGY_ID))
                .isEqualTo(ExecutionMode.PAPER);
    }
}
