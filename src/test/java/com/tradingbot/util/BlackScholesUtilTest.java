package com.tradingbot.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

class BlackScholesUtilTest {

    @Test
    void testAtmCallAndPutGreeks() {
        double spot = 25000.0;
        double strike = 25000.0;
        double tte = 30.0 / 365.0; // 30 days
        double iv = 0.15; // 15% IV
        double r = 0.07; // 7% risk-free rate

        double callDelta = BlackScholesUtil.calculateDelta(spot, strike, tte, iv, r, true);
        double putDelta = BlackScholesUtil.calculateDelta(spot, strike, tte, iv, r, false);

        assertThat(callDelta).isCloseTo(0.53, within(0.05));
        assertThat(putDelta).isCloseTo(-0.47, within(0.05));
        assertThat(callDelta - putDelta).isCloseTo(1.0, within(0.01));

        double callPrice = BlackScholesUtil.calculateOptionPrice(spot, strike, tte, iv, r, true);
        double putPrice = BlackScholesUtil.calculateOptionPrice(spot, strike, tte, iv, r, false);

        assertThat(callPrice).isGreaterThan(300.0);
        assertThat(putPrice).isGreaterThan(250.0);
    }

    @Test
    void testOtmDelta() {
        double spot = 25000.0;
        double tte = 20.0 / 365.0;
        double iv = 0.14;
        double r = 0.07;

        // OTM Put (strike 24300)
        double putDelta = BlackScholesUtil.calculateDelta(spot, 24300.0, tte, iv, r, false);
        assertThat(Math.abs(putDelta)).isBetween(0.15, 0.30);

        // OTM Call (strike 25700)
        double callDelta = BlackScholesUtil.calculateDelta(spot, 25700.0, tte, iv, r, true);
        assertThat(callDelta).isBetween(0.15, 0.30);
    }
}
