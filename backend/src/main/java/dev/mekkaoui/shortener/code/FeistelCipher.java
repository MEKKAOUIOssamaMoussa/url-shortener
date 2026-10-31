package dev.mekkaoui.shortener.code;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;

/**
 * Keyed Feistel permutation on 42 bits with cycle-walking over the domain [0, 62^7).
 * <p>
 * Splits a 42-bit integer into two 21-bit halves across 4 Feistel rounds using HMAC-SHA256
 * truncated to 21 bits as the round function. Cycle-walking repeatedly applies the permutation
 * until the result falls strictly within the Base62 7-character domain M = 62^7 (3,521,614,606,208).
 * Because the underlying construction is a true permutation on 42-bit space, cycle-walking is
 * guaranteed to terminate, with an expected iteration count of 2^42 / 62^7 ≈ 1.25.
 * </p>
 * <p>
 * <b>Security Note:</b> This is a custom format-preserving permutation designed for sequence
 * obfuscation and non-enumerable short URL codes; it is not a standardized NIST FPE scheme
 * (such as FF1 or FF3-1). Production environments must configure a strong, private secret key.
 * Changing the secret key on a live database only risks potential collisions with already-persisted
 * codes, which the application's unique database constraint and retry loop already handle safely.
 * </p>
 */
public class FeistelCipher {

    static final int HALF_BITS = 21;
    static final long HALF_MASK = (1L << HALF_BITS) - 1; // 0x1FFFFF
    static final int ROUNDS = 4;
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final SecretKeySpec keySpec;
    private final ThreadLocal<Mac> macHolder;

    public FeistelCipher(String secret) {
        Objects.requireNonNull(secret, "secret must not be null");
        byte[] secretBytes = secret.getBytes(StandardCharsets.UTF_8);
        this.keySpec = new SecretKeySpec(secretBytes, HMAC_ALGORITHM);
        this.macHolder = ThreadLocal.withInitial(() -> {
            try {
                Mac mac = Mac.getInstance(HMAC_ALGORITHM);
                mac.init(this.keySpec);
                return mac;
            } catch (NoSuchAlgorithmException | InvalidKeyException ex) {
                throw new IllegalStateException("Failed to initialize " + HMAC_ALGORITHM, ex);
            }
        });
    }

    /**
     * Obfuscates a sequence value into a pseudorandom integer in [0, M) using Feistel cycle-walking.
     *
     * @param x input sequence value in [0, M)
     * @return scrambled output in [0, M)
     */
    public long encrypt(long x) {
        if (x < 0 || x >= Base62.DOMAIN_M) {
            throw new IllegalArgumentException("Input must be in [0, " + Base62.DOMAIN_M + "), got: " + x);
        }
        long current = x;
        do {
            current = permute(current);
        } while (current >= Base62.DOMAIN_M);
        return current;
    }

    /**
     * Inverts the obfuscation, mapping a scrambled integer back to its sequence value.
     *
     * @param y scrambled value in [0, M)
     * @return original sequence value in [0, M)
     */
    public long decrypt(long y) {
        if (y < 0 || y >= Base62.DOMAIN_M) {
            throw new IllegalArgumentException("Input must be in [0, " + Base62.DOMAIN_M + "), got: " + y);
        }
        long current = y;
        do {
            current = permuteInverse(current);
        } while (current >= Base62.DOMAIN_M);
        return current;
    }

    /**
     * 4-round Feistel permutation on 42-bit integer.
     */
    long permute(long v) {
        long l = (v >>> HALF_BITS) & HALF_MASK;
        long r = v & HALF_MASK;
        for (int round = 0; round < ROUNDS; round++) {
            long nextR = (l ^ roundFunction(round, r)) & HALF_MASK;
            l = r;
            r = nextR;
        }
        return (l << HALF_BITS) | r;
    }

    /**
     * Inverse 4-round Feistel permutation on 42-bit integer.
     */
    long permuteInverse(long v) {
        long l = (v >>> HALF_BITS) & HALF_MASK;
        long r = v & HALF_MASK;
        for (int round = ROUNDS - 1; round >= 0; round--) {
            long prevL = (r ^ roundFunction(round, l)) & HALF_MASK;
            r = l;
            l = prevL;
        }
        return (l << HALF_BITS) | r;
    }

    /**
     * Round function: HMAC-SHA256 over (round number, half), truncated to 21 bits.
     */
    private long roundFunction(int round, long half) {
        Mac mac = macHolder.get();
        byte[] data = new byte[5];
        data[0] = (byte) round;
        data[1] = (byte) (half >>> 24);
        data[2] = (byte) (half >>> 16);
        data[3] = (byte) (half >>> 8);
        data[4] = (byte) half;
        byte[] digest = mac.doFinal(data);
        return (((digest[0] & 0x1F) << 16) | ((digest[1] & 0xFF) << 8) | (digest[2] & 0xFF)) & HALF_MASK;
    }
}
