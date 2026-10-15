package com.roadwise.backend.util;

import java.io.ByteArrayOutputStream;
import java.util.Locale;

/**
 * RFC 4648 Base32 (unpadded), the encoding authenticator apps expect for TOTP
 * shared secrets.
 *
 * Hand-rolled rather than pulling in a dependency: the alternative is Commons
 * Codec or Guava for two short methods, and this keeps the MFA feature free of
 * new artifacts.
 */
public final class Base32 {

    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    private Base32() {
    }

    public static String encode(byte[] data) {
        if (data == null || data.length == 0) {
            return "";
        }
        StringBuilder out = new StringBuilder((data.length * 8 + 4) / 5);
        int buffer = 0;
        int bitsLeft = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xFF);
            bitsLeft += 8;
            while (bitsLeft >= 5) {
                out.append(ALPHABET.charAt((buffer >> (bitsLeft - 5)) & 0x1F));
                bitsLeft -= 5;
            }
        }
        // Pad the trailing bits into a final symbol.
        if (bitsLeft > 0) {
            out.append(ALPHABET.charAt((buffer << (5 - bitsLeft)) & 0x1F));
        }
        return out.toString();
    }

    /**
     * Decodes Base32, tolerating the lower case, embedded spaces and '=' padding
     * that users produce when they copy a secret out of an authenticator app.
     *
     * @throws IllegalArgumentException when the input contains a character
     *         outside the Base32 alphabet.
     */
    public static byte[] decode(String encoded) {
        if (encoded == null) {
            return new byte[0];
        }
        String cleaned = encoded.trim()
                .replace("=", "")
                .replace(" ", "")
                .replace("-", "")
                .toUpperCase(Locale.ROOT);
        if (cleaned.isEmpty()) {
            return new byte[0];
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream((cleaned.length() * 5) / 8);
        int buffer = 0;
        int bitsLeft = 0;
        for (int i = 0; i < cleaned.length(); i++) {
            int value = ALPHABET.indexOf(cleaned.charAt(i));
            if (value < 0) {
                throw new IllegalArgumentException("Invalid Base32 character: " + cleaned.charAt(i));
            }
            buffer = (buffer << 5) | value;
            bitsLeft += 5;
            if (bitsLeft >= 8) {
                out.write((buffer >> (bitsLeft - 8)) & 0xFF);
                bitsLeft -= 8;
            }
        }
        return out.toByteArray();
    }
}
