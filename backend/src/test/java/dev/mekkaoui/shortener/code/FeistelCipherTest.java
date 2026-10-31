package dev.mekkaoui.shortener.code;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FeistelCipherTest {

    private static final String DEFAULT_SECRET = "dev-secret-key-must-be-changed-in-production-32-chars";
    private static final long M = Base62.DOMAIN_M;

    private final FeistelCipher cipher = new FeistelCipher(DEFAULT_SECRET);

    @ParameterizedTest
    @ValueSource(longs = {0L, 1L, 61L, 62L, 3521614606207L})
    void shouldBeBijectionForBoundaryValuesViaInverse(long value) {
        long encrypted = cipher.encrypt(value);
        assertThat(encrypted).isBetween(0L, M - 1);
        long decrypted = cipher.decrypt(encrypted);
        assertThat(decrypted).isEqualTo(value);
    }

    @Test
    void shouldBeBijectionForRandomValuesViaInverse() {
        Random random = new Random(12345);
        for (int i = 0; i < 5000; i++) {
            long value = Math.abs(random.nextLong()) % M;
            long encrypted = cipher.encrypt(value);
            assertThat(encrypted).isBetween(0L, M - 1);
            long decrypted = cipher.decrypt(encrypted);
            assertThat(decrypted).isEqualTo(value);
        }
    }

    @Test
    void shouldProduceNoDuplicatesAndStayWithinDomainOverOneMillionConsecutiveInputs() {
        int count = 1_000_000;
        long[] outputs = new long[count];
        long totalIterations = 0;

        for (int i = 0; i < count; i++) {
            long current = i;
            int iterations = 0;
            do {
                current = cipher.permute(current);
                iterations++;
            } while (current >= M);
            totalIterations += iterations;
            outputs[i] = current;
            assertThat(current).isBetween(0L, M - 1);
        }

        double avgIterations = (double) totalIterations / count;
        System.out.printf("Average cycle-walking iterations over %,d inputs: %.5f%n", count, avgIterations);
        assertThat(avgIterations).isBetween(1.20, 1.30);

        Arrays.sort(outputs);
        for (int i = 0; i < count - 1; i++) {
            assertThat(outputs[i])
                    .withFailMessage("Duplicate found at index %d: %d", i, outputs[i])
                    .isNotEqualTo(outputs[i + 1]);
        }
    }

    @Test
    void shouldNotProduceConstantStepApartAndRarelyShareThreeCharPrefix() {
        int count = 1000;
        long[] outputs = new long[count];
        List<String> codes = new ArrayList<>(count);

        for (int i = 0; i < count; i++) {
            long scrambled = cipher.encrypt(i);
            outputs[i] = scrambled;
            codes.add(Base62.encode(scrambled, 7));
        }

        Set<Long> diffs = new HashSet<>();
        for (int i = 0; i < count - 1; i++) {
            diffs.add(outputs[i + 1] - outputs[i]);
        }
        assertThat(diffs.size())
                .withFailMessage("Consecutive outputs must not have a constant step apart")
                .isGreaterThan(500);

        long shared3CharPrefixCount = IntStream.range(0, count - 1)
                .filter(i -> codes.get(i).substring(0, 3).equals(codes.get(i + 1).substring(0, 3)))
                .count();
        assertThat(shared3CharPrefixCount).isLessThanOrEqualTo(2);
    }

    @Test
    void shouldProduceDifferentPermutationsForDifferentSecrets() {
        FeistelCipher cipherAlpha = new FeistelCipher("secret-alpha-key-at-least-16-chars");
        FeistelCipher cipherBeta = new FeistelCipher("secret-beta--key-at-least-16-chars");

        int differingOutputs = 0;
        for (int i = 0; i < 100; i++) {
            if (cipherAlpha.encrypt(i) != cipherBeta.encrypt(i)) {
                differingOutputs++;
            }
        }
        assertThat(differingOutputs).isGreaterThanOrEqualTo(98);
    }

    @Test
    void shouldRejectInputsOutOfDomain() {
        assertThatThrownBy(() -> cipher.encrypt(-1L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> cipher.encrypt(M))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> cipher.decrypt(-1L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> cipher.decrypt(M))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
