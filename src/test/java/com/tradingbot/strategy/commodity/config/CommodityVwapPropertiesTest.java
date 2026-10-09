package com.tradingbot.strategy.commodity.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CommodityVwapPropertiesTest {

    @Test
    @DisplayName("Should initialize default property values for 1:30 PM Commodity VWAP Strategy")
    void defaultValues() {
        CommodityVwapProperties props = new CommodityVwapProperties();

        assertThat(props.isEnabled()).isTrue();
        assertThat(props.getSymbols()).containsExactly("SILVER", "CRUDEOIL");
        assertThat(props.getPcrBullishMin()).isEqualTo(1.15);
        assertThat(props.getPcrBearishMax()).isEqualTo(0.85);
        assertThat(props.getRiskRewardRatio()).isEqualTo(2.5);
        assertThat(props.getEntryCutoff()).isEqualTo(LocalTime.of(22, 30));
        assertThat(props.getEodSquareOffTime()).isEqualTo(LocalTime.of(23, 15));
        assertThat(props.getMaxTradesPerSymbol()).isEqualTo(1);
        assertThat(props.isTelegramAlertsEnabled()).isTrue();
    }

    @Test
    @DisplayName("Should allow updating property values via setters")
    void settersAndGetters() {
        CommodityVwapProperties props = new CommodityVwapProperties();
        props.setEnabled(false);
        props.setSymbols(List.of("COPPER", "NATURALGAS"));
        props.setPcrBullishMin(1.20);
        props.setPcrBearishMax(0.80);
        props.setRiskRewardRatio(2.5);
        props.setEntryCutoff(LocalTime.of(22, 0));
        props.setEodSquareOffTime(LocalTime.of(23, 0));
        props.setMaxTradesPerSymbol(2);
        props.setTelegramAlertsEnabled(false);

        assertThat(props.isEnabled()).isFalse();
        assertThat(props.getSymbols()).containsExactly("COPPER", "NATURALGAS");
        assertThat(props.getPcrBullishMin()).isEqualTo(1.20);
        assertThat(props.getPcrBearishMax()).isEqualTo(0.80);
        assertThat(props.getRiskRewardRatio()).isEqualTo(2.5);
        assertThat(props.getEntryCutoff()).isEqualTo(LocalTime.of(22, 0));
        assertThat(props.getEodSquareOffTime()).isEqualTo(LocalTime.of(23, 0));
        assertThat(props.getMaxTradesPerSymbol()).isEqualTo(2);
        assertThat(props.isTelegramAlertsEnabled()).isFalse();
    }
}
