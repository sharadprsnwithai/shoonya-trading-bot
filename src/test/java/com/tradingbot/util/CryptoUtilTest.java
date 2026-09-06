package com.tradingbot.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CryptoUtilTest {

    @Test
    void testSha256() {
        String hash = CryptoUtil.sha256("hello world");
        assertThat(hash)
                .isEqualTo("b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9");
    }

    @Test
    void testBase32Decode() {
        byte[] bytes = CryptoUtil.base32Decode("JBSWY3DP");
        assertThat(bytes).isNotEmpty();
        assertThat(new String(bytes)).isEqualTo("Hello");
    }

    @Test
    void testTotpGeneration() {
        String secret = "JBSWY3DPEHPK3PXP";
        String totp = CryptoUtil.generateTotp(secret);
        assertThat(totp).isNotNull();
        assertThat(totp).matches("^\\d{6}$");
    }

    @Test
    void testDirectNumericTotp() {
        String directCode = "654321";
        String result = CryptoUtil.generateTotp(directCode);
        assertThat(result).isEqualTo("654321");
    }

    @Test
    void testDeriveShoonyaAppKey() {
        String appKey = CryptoUtil.deriveShoonyaAppKey("FA142963");
        assertThat(appKey).isNotNull().hasSize(64);
    }
}
