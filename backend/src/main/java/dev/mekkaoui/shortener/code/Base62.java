package dev.mekkaoui.shortener.code;

import java.util.Arrays;

public final class Base62 {

    public static final String ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    public static final int BASE = ALPHABET.length();
    public static final long DOMAIN_M = 3_521_614_606_208L; // 62^7

    private static final int[] DECODE_TABLE = new int[128];

    static {
        Arrays.fill(DECODE_TABLE, -1);
        for (int i = 0; i < ALPHABET.length(); i++) {
            DECODE_TABLE[ALPHABET.charAt(i)] = i;
        }
    }

    private Base62() {
    }

    public static String encode(long value, int length) {
        if (length <= 0) {
            throw new IllegalArgumentException("Length must be positive, got: " + length);
        }
        if (value < 0) {
            throw new IllegalArgumentException("Value must be non-negative, got: " + value);
        }
        long max = 1L;
        for (int i = 0; i < length; i++) {
            if (max > Long.MAX_VALUE / BASE) {
                max = Long.MAX_VALUE;
                break;
            }
            max *= BASE;
        }
        if (value >= max) {
            throw new IllegalArgumentException("Value " + value + " exceeds capacity for length " + length);
        }
        char[] chars = new char[length];
        long remainder = value;
        for (int i = length - 1; i >= 0; i--) {
            chars[i] = ALPHABET.charAt((int) (remainder % BASE));
            remainder /= BASE;
        }
        return new String(chars);
    }

    public static long decode(String str) {
        if (str == null || str.isEmpty()) {
            throw new IllegalArgumentException("Input string must not be null or empty");
        }
        long result = 0;
        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);
            if (c >= DECODE_TABLE.length || DECODE_TABLE[c] < 0) {
                throw new IllegalArgumentException("Invalid Base62 character: " + c);
            }
            int digit = DECODE_TABLE[c];
            if (result > (Long.MAX_VALUE - digit) / BASE) {
                throw new IllegalArgumentException("Base62 string overflows long: " + str);
            }
            result = result * BASE + digit;
        }
        return result;
    }
}
