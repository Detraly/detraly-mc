package com.detraly.analytics.storage;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class TokenFingerprint {
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private TokenFingerprint() {
    }

    public static String of(String token) {
        if (token == null || token.isEmpty()) {
            return null;
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            char[] out = new char[16];
            for (int i = 0; i < 8; i++) {
                out[i * 2] = HEX[(digest[i] >> 4) & 0xF];
                out[i * 2 + 1] = HEX[digest[i] & 0xF];
            }
            return new String(out);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }
}
