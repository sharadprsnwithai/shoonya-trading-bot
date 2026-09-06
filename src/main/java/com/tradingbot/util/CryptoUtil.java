package com.tradingbot.util;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Utility class for cryptographic operations: - SHA-256 hashing - Base32 decoding - RFC 6238 TOTP
 * generation - Shoonya derived app key calculation
 */
public final class CryptoUtil {

    private static final int[] SHOONYA_KEY_OFFSETS = {83, 50, 97, 114, 110, 46, 27, 93};
    private static final String BASE32_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    private CryptoUtil() {
        // Prevent instantiation
    }

    /** Calculates the SHA-256 hexadecimal string of the given input. */
    public static String sha256(String input) {
        if (input == null) {
            return "";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                String h = Integer.toHexString(0xff & b);
                if (h.length() == 1) {
                    hex.append('0');
                }
                hex.append(h);
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 algorithm unavailable", e);
        }
    }

    /** Derives Shoonya's proprietary appkey from the user ID. */
    public static String deriveShoonyaAppKey(String userId) {
        StringBuilder keyBuilder = new StringBuilder(userId).append("|");
        for (int p = 0; p < SHOONYA_KEY_OFFSETS.length; p++) {
            keyBuilder.append((char) (SHOONYA_KEY_OFFSETS[p] + p));
        }
        return sha256(keyBuilder.toString());
    }

    /** Generates a 6-digit TOTP from a Base32-encoded secret or returns the raw code if numeric. */
    public static String generateTotp(String secret) {
        if (secret == null || secret.isBlank()) {
            return null;
        }
        String cleanSecret = secret.trim().replace(" ", "");
        if (cleanSecret.matches("^\\d{6}$")) {
            return cleanSecret;
        }
        try {
            byte[] key = base32Decode(cleanSecret);
            long timeStep = System.currentTimeMillis() / 1000L / 30L;
            byte[] timeBytes = new byte[8];
            for (int i = 7; i >= 0; i--) {
                timeBytes[i] = (byte) (timeStep & 0xFF);
                timeStep >>= 8;
            }

            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            byte[] hash = mac.doFinal(timeBytes);

            int offset = hash[hash.length - 1] & 0x0F;
            int binary =
                    ((hash[offset] & 0x7F) << 24)
                            | ((hash[offset + 1] & 0xFF) << 16)
                            | ((hash[offset + 2] & 0xFF) << 8)
                            | (hash[offset + 3] & 0xFF);

            int otp = binary % 1000000;
            return String.format("%06d", otp);
        } catch (Exception e) {
            return cleanSecret;
        }
    }

    /** Decodes an RFC 4648 Base32 string into raw bytes. */
    public static byte[] base32Decode(String base32) {
        String clean = base32.toUpperCase().replaceAll("[^A-Z2-7]", "");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int buffer = 0;
        int bitsLeft = 0;

        for (char c : clean.toCharArray()) {
            int val = BASE32_CHARS.indexOf(c);
            if (val < 0) {
                continue;
            }
            buffer = (buffer << 5) | val;
            bitsLeft += 5;
            if (bitsLeft >= 8) {
                bytes.write((buffer >> (bitsLeft - 8)) & 0xFF);
                bitsLeft -= 8;
            }
        }
        return bytes.toByteArray();
    }
}
